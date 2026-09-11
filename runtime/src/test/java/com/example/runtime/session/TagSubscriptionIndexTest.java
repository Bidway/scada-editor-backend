package com.example.runtime.session;

import com.example.runtime.client.dto.EditorComponentDto;
import com.example.runtime.client.dto.EditorPropertyDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Резолв короткого пути тега (writeProjectTag): общий префикс проекта считается один раз
 * при построении индекса как наибольший общий по сегментам префикс среди уже известных
 * tag_id, с защитой от вырожденных случаев — префикс не может занять больше, чем
 * (минимум сегментов среди известных тегов − 1), иначе при бедном разнообразии тегов
 * в проекте он мог бы «съесть» весь путь целиком и сломать обратную операцию.
 */
class TagSubscriptionIndexTest {

    @Test
    @DisplayName("короткий путь дополняется общим префиксом разнородных тегов проекта")
    void shortPathIsPrefixedWithCommonProjectPrefix() {
        TagSubscriptionIndex index = indexWithTags(
                "Барановичи-1.BN1_MCA1.FQT_ST.LINE1FQT1.ST",
                "Барановичи-1.BN1_MCA1.V_ST_1.LINE1V0.ST",
                "Барановичи-1.BN1_MCA1.AI_M.AI2.M");

        // Тег не обязан уже быть известным индексу — резолв работает и для того, что
        // ни к одному свойству дерева не привязано: в этом и смысл функции.
        assertThat(index.resolveTagPath("FQT_ST.LINE2FQT9.ST"))
                .isEqualTo("Барановичи-1.BN1_MCA1.FQT_ST.LINE2FQT9.ST");
    }

    @Test
    @DisplayName("уже полный путь текущего проекта не дополняется повторно")
    void fullPathAlreadyCarryingPrefixPassesThroughUnchanged() {
        TagSubscriptionIndex index = indexWithTags(
                "Барановичи-1.BN1_MCA1.FQT_ST.LINE1FQT1.ST",
                "Барановичи-1.BN1_MCA1.V_ST_1.LINE1V0.ST");

        String full = "Барановичи-1.BN1_MCA1.AI_M.AI9.M";
        assertThat(index.resolveTagPath(full)).isEqualTo(full);
    }

    @Test
    @DisplayName("единственный известный тег отдаёт префикс без своего последнего сегмента")
    void singleKnownTagPrefixExcludesOnlyLastSegment() {
        TagSubscriptionIndex index = indexWithTags("A.B.C.D.E");

        assertThat(index.resolveTagPath("E")).isEqualTo("A.B.C.D.E");
    }

    @Test
    @DisplayName("повтор одного и того же тега не даёт префиксу съесть тег целиком")
    void repeatedIdenticalTagDoesNotLetPrefixConsumeWholeTag() {
        // Несколько свойств дерева привязаны к одному и тому же тегу — наибольший общий
        // префикс совпадений совпал бы с самим тегом целиком, если бы не защита.
        TagSubscriptionIndex index = indexWithTags("A.B.C.D", "A.B.C.D", "A.B.C.D");

        assertThat(index.resolveTagPath("D")).isEqualTo("A.B.C.D");
    }

    @Test
    @DisplayName("без единого известного тега путь возвращается как есть")
    void noKnownTagsLeavesPathUnchanged() {
        TagSubscriptionIndex index = indexWithTags();

        assertThat(index.resolveTagPath("Foo.Bar")).isEqualTo("Foo.Bar");
    }

    @Test
    @DisplayName("свойство адресуется парой «имя компонента + имя свойства», одноимённые компоненты дают неоднозначность")
    void propertyIdsByComponentName_findsByNames_andReportsAmbiguity() {
        EditorComponentDto root = component(1L, "project", "BN1_MCA1");
        root.setChildren(List.of(
                component(2L, "table", "Параметры станции", property(10L, "CONCENTRATION_ALKALI")),
                component(3L, "group", "Насос", property(20L, "ST")),
                component(4L, "group", "Насос", property(30L, "ST"))));

        TagSubscriptionIndex index = TagSubscriptionIndex.build(root);

        assertThat(index.propertyIdsByComponentName("Параметры станции", "CONCENTRATION_ALKALI")).containsExactly(10L);
        assertThat(index.propertyIdsByComponentName("Насос", "ST")).containsExactlyInAnyOrder(20L, 30L);
        assertThat(index.propertyIdsByComponentName("Параметры станции", "НЕТ_ТАКОГО")).isEmpty();
    }

    private static EditorComponentDto component(long id, String type, String name, EditorPropertyDto... properties) {
        EditorComponentDto component = new EditorComponentDto();
        component.setId(id);
        component.setType(type);
        component.setName(name);
        component.setProperties(List.of(properties));
        return component;
    }

    private static EditorPropertyDto property(long id, String name) {
        EditorPropertyDto property = new EditorPropertyDto();
        property.setId(id);
        property.setName(name);
        return property;
    }

    private static TagSubscriptionIndex indexWithTags(String... tagIds) {
        EditorComponentDto root = new EditorComponentDto();
        root.setId(1L);
        root.setType("project");

        List<EditorPropertyDto> properties = new java.util.ArrayList<>();
        long propertyId = 100L;
        for (String tagId : tagIds) {
            EditorPropertyDto property = new EditorPropertyDto();
            property.setId(propertyId++);
            property.setName("p" + propertyId);
            property.setTag_id(tagId);
            properties.add(property);
        }
        root.setProperties(properties);

        return TagSubscriptionIndex.build(root);
    }
}
