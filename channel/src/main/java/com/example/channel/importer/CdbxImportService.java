package com.example.channel.importer;

import com.example.channel.exception.NotFoundException;
import com.example.channel.repository.NodeRepository;
import com.example.channel.repository.ParamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Импорт .cdbx в новую объектную базу каналов и удаление такой базы. Файл разбирается и план
 * строится целиком до первой записи: битый файл не оставляет полбазы.
 */
@Service
@RequiredArgsConstructor
public class CdbxImportService {

    /** Поля драйвера → параметры узла проекта. */
    private static final Map<String, String> DRIVER_PARAMS = Map.of(
            "enabled", ImportParamTypes.ENABLED,
            "access", ImportParamTypes.ACCESS,
            "descr", ImportParamTypes.DESCRIPTION,
            "maxsubtypescount", ImportParamTypes.MAX_CHANNELS,
            "PORTNAME", ImportParamTypes.PORT_NAME,
            "SPEED", ImportParamTypes.SPEED,
            "PARITY", ImportParamTypes.PARITY,
            "DATABITS", ImportParamTypes.DATA_BITS,
            "STOPBITS", ImportParamTypes.STOP_BITS);

    private final NodeRepository nodeRepository;
    private final ParamRepository paramRepository;
    private final ImportParamTypes paramTypes;
    private final CdbxImportWriter writer;

    @Transactional
    public CdbxImportReport importFile(byte[] content, String fileName, String site, String project) {
        return importFile(content, fileName, site, project, PlcProject.empty());
    }

    @Transactional
    public CdbxImportReport importFile(byte[] content, String fileName, String site, String project,
                                       PlcProject plc) {
        String siteName = segment(site, "Площадка");
        String projectName = segment(project, "Проект");
        CdbxFile file = CdbxParser.parse(content);
        String root = siteName + "." + projectName;
        if (nodeRepository.findByIdNode(root).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Проект " + root + " уже есть: импорт только создаёт новую базу");
        }
        Map<String, Long> types = paramTypes.ids();

        Set<String> nodes = new LinkedHashSet<>();
        List<PlannedParam> params = new ArrayList<>();
        if (nodeRepository.findByIdNode(siteName).isEmpty()) {
            nodes.add(siteName);
        }
        nodes.add(root);
        params.add(new PlannedParam(root, types.get(ImportParamTypes.SOURCE), fileName == null ? "" : fileName));
        file.driver().forEach((field, value) -> {
            String type = DRIVER_PARAMS.get(field);
            if (type != null) {
                params.add(new PlannedParam(root, types.get(type), value));
            }
        });

        Set<String> channelPaths = new HashSet<>();
        List<String> merged = new ArrayList<>();
        List<String> guessed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Set<String> unmapped = new LinkedHashSet<>();
        for (CdbxChannel channel : file.channels()) {
            ObjectPathMapper.LegacyName legacy = ObjectPathMapper.split(channel.name()).orElse(null);
            if (legacy == null) {
                skipped.add(channel.name());
                continue;
            }
            if (!plc.isEmpty() && !ObjectPathMapper.known(legacy.object(), plc)) {
                unmapped.add(legacy.object());
            }
            String parent = root;
            for (String segment : ObjectPathMapper.objectSegments(legacy.object(), plc)) {
                parent = parent + "." + segment;
                nodes.add(parent);
            }
            String path = parent + "." + legacy.field();
            if (!channelPaths.add(path)) {
                merged.add(channel.name());
                continue;
            }
            nodes.add(path);
            DataTypeResolver.Resolved dataType = DataTypeResolver.resolve(legacy.field());
            if (dataType.guessed()) {
                guessed.add(channel.name());
            }
            params.add(new PlannedParam(path, types.get(ImportParamTypes.ENABLED), channel.enabled()));
            params.add(new PlannedParam(path, types.get(ImportParamTypes.REQUEST_TYPE), channel.requestType()));
            params.add(new PlannedParam(path, types.get(ImportParamTypes.PERIOD), channel.requestPeriod()));
            params.add(new PlannedParam(path, types.get(ImportParamTypes.DELTA), channel.delta()));
            params.add(new PlannedParam(path, types.get(ImportParamTypes.AVERAGING), channel.appTime()));
            params.add(new PlannedParam(path, types.get(ImportParamTypes.PROTOCOL), channel.protocol()));
            params.add(new PlannedParam(path, types.get(ImportParamTypes.DESCRIPTION),
                    description(channel, legacy.object(), plc)));
            params.add(new PlannedParam(path, types.get(ImportParamTypes.PLC_NAME), channel.name()));
            params.add(new PlannedParam(path, types.get(ImportParamTypes.DATA_TYPE), dataType.type()));
        }

        writer.write(new ArrayList<>(nodes), params);
        return new CdbxImportReport(root, nodes.size(), channelPaths.size(), merged, guessed, skipped,
                new ArrayList<>(unmapped));
    }

    /**
     * Описание канала своё, из .cdbx; если его там нет — описание прибора из main.io.lua. В базе
     * танков описаний у каналов нет вовсе, и только вложение делает дерево читаемым.
     */
    private static String description(CdbxChannel channel, String object, PlcProject plc) {
        if (channel.description() != null && !channel.description().isBlank()) {
            return channel.description();
        }
        return plc.device(object).map(PlcProject.Device::description).orElse(channel.description());
    }

    /**
     * Проект целиком — вместо отмены импорта, которого нет в журнале. Только проект с параметром
     * «Источник импорта»: так не снести базу, собранную руками или залитую дампом.
     */
    @Transactional
    public void deleteProject(String site, String project) {
        String root = segment(site, "Площадка") + "." + segment(project, "Проект");
        if (nodeRepository.findByIdNode(root).isEmpty()) {
            throw new NotFoundException("Нет проекта " + root);
        }
        long sourceType = paramTypes.ids().get(ImportParamTypes.SOURCE);
        boolean imported = paramRepository.findByIdNode(root).orElse(List.of()).stream()
                .anyMatch(param -> param.getIdType() != null && param.getIdType() == sourceType);
        if (!imported) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Проект " + root + " создан не импортом .cdbx — удаление целиком запрещено");
        }
        writer.deleteTree(root);
    }

    private static String segment(String value, String what) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty() || trimmed.contains(".")) {
            throw new IllegalArgumentException(what + " — непустое имя без точки");
        }
        return trimmed;
    }
}
