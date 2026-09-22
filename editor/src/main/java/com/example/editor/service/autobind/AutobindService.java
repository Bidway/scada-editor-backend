package com.example.editor.service.autobind;

import com.example.editor.client.ChannelClient;
import com.example.editor.dto.autobind.AutobindReportDto;
import com.example.editor.exception.NotFoundException;
import com.example.editor.model.ProjectRuntimeFlag;
import com.example.editor.model.component.Component;
import com.example.editor.model.component.ComponentProperty;
import com.example.editor.model.component.ComponentTypes;
import com.example.editor.model.version.DocumentType;
import com.example.editor.model.version.VersionKind;
import com.example.editor.repository.ProjectRuntimeFlagRepository;
import com.example.editor.repository.component.ComponentPropertyRepository;
import com.example.editor.repository.component.ComponentRepository;
import com.example.editor.service.version.DocumentVersionService;
import com.example.editor.service.version.SceneDocumentSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Автопривязка проекта к базе каналов: компонент по имени находит объект, теговое свойство по
 * имени — поле. Совпало — tag_id перезаписывается; нет — остаётся и идёт в отчёт.
 * <p>
 * База читается до транзакции: channel лёг — 503, ничего не записано, и соединение с базой не
 * держится на время HTTP-запроса. Запись — одна транзакция со сценами под блокировкой строки, той
 * же, что у сохранения сцены (findByIdForUpdate), и версией на каждую изменённую сцену.
 */
@Service
public class AutobindService {

    static final String TAG = "Тег";
    static final String VARIABLE_PREFIX = "@var.";

    private final ChannelClient channelClient;
    private final ComponentRepository componentRepository;
    private final ComponentPropertyRepository propertyRepository;
    private final DocumentVersionService versionService;
    private final SceneDocumentSource sceneDocumentSource;
    private final ProjectRuntimeFlagRepository runtimeFlags;
    private final TransactionTemplate transaction;

    public AutobindService(ChannelClient channelClient, ComponentRepository componentRepository,
                           ComponentPropertyRepository propertyRepository, DocumentVersionService versionService,
                           SceneDocumentSource sceneDocumentSource, ProjectRuntimeFlagRepository runtimeFlags,
                           PlatformTransactionManager transactionManager) {
        this.channelClient = channelClient;
        this.componentRepository = componentRepository;
        this.propertyRepository = propertyRepository;
        this.versionService = versionService;
        this.sceneDocumentSource = sceneDocumentSource;
        this.runtimeFlags = runtimeFlags;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public AutobindReportDto autobind(Long projectId, String channelRoot, String userName) {
        String root = channelRoot == null ? "" : channelRoot.trim();
        if (root.isEmpty()) {
            throw new IllegalArgumentException("channel_root — узел проекта в базе каналов, пустым не бывает");
        }
        componentRepository.findById(projectId)
                .filter(component -> ComponentTypes.PROJECT.equals(component.getType()))
                .orElseThrow(() -> new NotFoundException("Нет проекта " + projectId));
        ChannelIndex index = ChannelIndex.build(root, channelClient.fetchTree(root));
        if (index.isEmpty()) {
            throw new NotFoundException("В базе каналов нет каналов под " + root);
        }
        return transaction.execute(status -> bind(projectId, root, index, userName));
    }

    private AutobindReportDto bind(Long projectId, String root, ChannelIndex index, String userName) {
        Counters counters = new Counters();
        List<AutobindReportDto.Scene> scenes = new ArrayList<>();
        for (Component listed : componentRepository.findByParentIdAndType(projectId, ComponentTypes.SCENE)) {
            Component scene = componentRepository.findByIdForUpdate(listed.getId()).orElseThrow();
            int changedBefore = counters.changed;
            walk(scene, scene.getName(), index, counters);
            if (counters.changed > changedBefore) {
                // flush до снимка: contentOf читает дерево из базы, без него снимок не увидит правок.
                propertyRepository.flush();
                Integer versionNo = versionService.record(DocumentType.SCENE, scene.getId(),
                        sceneDocumentSource.contentOf(scene.getId()), userName, VersionKind.MANUAL, null, null)
                        .getVersionNo();
                scenes.add(new AutobindReportDto.Scene(scene.getId(), scene.getName(), versionNo));
            }
        }
        boolean inOperation = runtimeFlags.findById(projectId).map(ProjectRuntimeFlag::isInOperation).orElse(false);
        return new AutobindReportDto(root, counters.bound, counters.changed, scenes, counters.notFound,
                counters.missingFields, inOperation);
    }

    private void walk(Component component, String sceneName, ChannelIndex index, Counters counters) {
        List<ComponentProperty> tagProperties = component.getProperties().stream()
                .filter(property -> TAG.equals(property.getPropertyType()))
                .filter(property -> property.getTagId() == null || !property.getTagId().startsWith(VARIABLE_PREFIX))
                .toList();
        if (!tagProperties.isEmpty()) {
            Optional<String> object = index.objectOf(component.getName());
            if (object.isEmpty()) {
                counters.notFound.add(new AutobindReportDto.Miss(component.getId(), component.getName(), null, sceneName));
            } else {
                for (ComponentProperty property : tagProperties) {
                    Optional<String> tag = index.tagOf(object.get(), property.getName());
                    if (tag.isEmpty()) {
                        counters.missingFields.add(new AutobindReportDto.Miss(component.getId(), component.getName(),
                                property.getName(), sceneName));
                        continue;
                    }
                    counters.bound++;
                    if (!tag.get().equals(property.getTagId())) {
                        property.setTagId(tag.get());
                        counters.changed++;
                    }
                }
            }
        }
        for (Component child : component.getChildren()) {
            walk(child, sceneName, index, counters);
        }
    }

    private static final class Counters {
        int bound;
        int changed;
        final List<AutobindReportDto.Miss> notFound = new ArrayList<>();
        final List<AutobindReportDto.Miss> missingFields = new ArrayList<>();
    }
}
