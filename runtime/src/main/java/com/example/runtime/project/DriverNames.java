package com.example.runtime.project;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Драйвер — первый сегмент пути тега: «Барановичи-1.BN1_MCA1.V_ST_1.LINE1V0.ST» → «Барановичи-1».
 * Это группа контроллеров в controllers.yaml шлюза и единица владения: на этапе 2 у каждого
 * драйвера будет свой топик телеметрии, и экземпляр runtime будет читать только свои.
 */
public final class DriverNames {

    private DriverNames() {
    }

    public static String of(String tagId) {
        if (tagId == null || tagId.isBlank()) {
            return null;
        }
        int dot = tagId.indexOf('.');
        return dot < 0 ? tagId : tagId.substring(0, dot);
    }

    public static Set<String> of(Iterable<String> tagIds) {
        Set<String> drivers = new LinkedHashSet<>();
        for (String tagId : tagIds) {
            String driver = of(tagId);
            if (driver != null) {
                drivers.add(driver);
            }
        }
        return drivers;
    }
}
