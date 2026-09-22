package com.example.channel.importer;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Тип данных тега для реестра шлюза по имени поля. Таблица снята с controllers.yaml
 * scada-gateway: у 1236 тегов ptusa она выполняется без исключений. Флагу IsString из .cdbx
 * не доверяем — у CUR_REC там 0, хотя это строка.
 */
public final class DataTypeResolver {

    public record Resolved(String type, boolean guessed) {
    }

    private static final Pattern INDEX = Pattern.compile("\\[[^\\]]*\\]");

    private static final Set<String> INT32 = Set.of("ST", "M", "R", "EST", "CMD", "OPER", "STATE", "BLINK",
            "OPENED", "CLOSED", "NAMUR_ST", "L_RED", "L_SIREN", "P_V_OFF_DELAY_TIME");
    private static final Set<String> STRING = Set.of("CUR_PRG", "CUR_REC", "LOADED_REC", "PRG_LIST", "REC_LIST");
    private static final Set<String> FLOAT = Set.of("V", "F", "T", "FRQ", "RPM", "ABS_V", "CLEVEL", "RT_PAR_F",
            "PAR_MAIN", "PAR_SELFCLEAN", "REC_PAR", "P_CZ", "P_DT", "P_ERR", "P_H_CONE", "P_MAX_FLOW",
            "P_MIN_FLOW", "P_MAX_P", "P_ON_TIME", "P_R");

    private DataTypeResolver() {
    }

    /** Поле вне таблицы — FLOAT с пометкой: тип правят параметром узла до выгрузки. */
    public static Resolved resolve(String field) {
        String base = INDEX.matcher(field).replaceAll("").trim();
        if (INT32.contains(base)) {
            return new Resolved("INT32", false);
        }
        if (STRING.contains(base)) {
            return new Resolved("STRING", false);
        }
        return new Resolved("FLOAT", !FLOAT.contains(base));
    }
}
