package com.example.channel.export;

import com.example.channel.exception.NotFoundException;
import com.example.channel.importer.ImportParamTypes;
import com.example.channel.importer.ObjectPathMapper;
import com.example.channel.model.Node;
import com.example.channel.model.NodeParam;
import com.example.channel.repository.NodeRepository;
import com.example.channel.repository.ParamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Блок контроллера для controllers.yaml scada-gateway: name — объектный путь (он же Kafka-key и
 * tag_id в editor), deviceName/fieldName — «Имя в ПЛК», под которым тег знает контроллер.
 * Вставку в controllers.yaml и пересборку шлюза делают руками.
 */
@Service
@RequiredArgsConstructor
public class GatewayExportService {

    private final NodeRepository nodeRepository;
    private final ParamRepository paramRepository;
    private final ImportParamTypes paramTypes;

    @Transactional
    public String export(String root, String controllerId, String endpoint) {
        Map<String, Long> types = paramTypes.ids();
        long plcNameType = types.get(ImportParamTypes.PLC_NAME);
        long dataTypeType = types.get(ImportParamTypes.DATA_TYPE);

        List<Node> nodes = nodeRepository.findByIdNodeStartingWith(root);
        Map<String, String> plcNames = new HashMap<>();
        Map<String, String> dataTypes = new HashMap<>();
        if (!nodes.isEmpty()) {
            for (NodeParam param : paramRepository.findParamsByNodeIds(nodes.stream().map(Node::getIdNode).toList())) {
                if (param.getIdType() == null || param.getValue() == null || param.getValue().isBlank()) {
                    continue;
                }
                if (param.getIdType() == plcNameType) {
                    plcNames.put(param.getIdNode(), param.getValue());
                } else if (param.getIdType() == dataTypeType) {
                    dataTypes.put(param.getIdNode(), param.getValue());
                }
            }
        }

        StringBuilder yaml = new StringBuilder()
                .append("    - id: ").append(controllerId).append('\n')
                .append("      name: \"").append(quote(root)).append("\"\n")
                .append("      endpoint: \"").append(quote(endpoint)).append("\"\n")
                .append("      enabled: true\n")
                .append("      tags:\n");
        int tags = 0;
        for (Node node : nodes) {
            String plcName = plcNames.get(node.getIdNode());
            ObjectPathMapper.LegacyName legacy = plcName == null ? null : ObjectPathMapper.split(plcName).orElse(null);
            if (legacy == null) {
                continue;
            }
            yaml.append("        - {name: \"").append(quote(node.getIdNode()))
                    .append("\", nodeId: \"pac:").append(node.getId())
                    .append("\", channelId: ").append(node.getId())
                    .append(", deviceName: \"").append(quote(legacy.object()))
                    .append("\", fieldName: \"").append(quote(legacy.field()))
                    .append("\", deviceType: \"").append(quote(ObjectPathMapper.deviceType(legacy.object())))
                    .append("\", protocol: pac, dataType: ").append(dataTypes.getOrDefault(node.getIdNode(), "FLOAT"))
                    .append(", pollingRate: 2000, enabled: true, writable: true}\n");
            tags++;
        }
        if (tags == 0) {
            throw new NotFoundException("Под " + root + " нет каналов с параметром «" + ImportParamTypes.PLC_NAME + "»");
        }
        return yaml.toString();
    }

    private static String quote(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
