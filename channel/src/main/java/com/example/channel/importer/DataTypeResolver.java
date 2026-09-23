package com.example.channel.importer;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Тип данных тега для реестра шлюза по имени поля.
 *
 * <p>Основа таблицы снята с {@code controllers.yaml} scada-gateway: у 2520 тегов ptusa она
 * выполняется без единого расхождения. Дополнена 23.09.2026 по базе танков нормализации молока,
 * где полей оказалось вдвое больше (`scada-3ebv`).
 *
 * <p>Цена ошибки несимметрична, отсюда и осторожность таблицы ({@code PacLua.read} шлюза):
 * строковое значение у тега, объявленного числом, возвращается как {@code null} — тег молча
 * пропадает; {@code INT32} у дробного поля обрезает дробную часть; {@code FLOAT} же безопасен
 * для целых. Поэтому {@code STRING} ставится всюду, где поле похоже на текст, {@code INT32} —
 * только там, где значение заведомо дискретно, а во всех сомнительных случаях остаётся
 * {@code FLOAT} по умолчанию.
 *
 * <p>Флагу {@code IsString} из .cdbx не доверяем — у {@code CUR_REC} там 0, хотя это строка.
 */
public final class DataTypeResolver {

    public record Resolved(String type, boolean guessed) {
    }

    private static final Pattern INDEX = Pattern.compile("\\[[^\\]]*\\]");

    /**
     * Шаги операций техобъекта: {@code RUN_STEPS1}…{@code RUN_STEPS30}, {@code IDLE_STEPS<n>},
     * {@code PAUSE_STEPS<n>}. Номер входит в имя поля, а не в индекс, поэтому семейство задано
     * образцом. Значение — маска активных шагов, дробным не бывает.
     */
    private static final List<Pattern> INT32_FAMILIES = List.of(
            Pattern.compile("^(RUN|IDLE|PAUSE)_STEPS\\d*$"));

    private static final Set<String> INT32 = Set.of(
            // приборы: состояние, команда, режим
            "ST", "M", "R", "EST", "CMD", "OPER", "STATE", "BLINK", "OPENED", "CLOSED", "NAMUR_ST",
            "FB_OFF_ST", "CS", "ERR",
            // лампы и сирена
            "L_RED", "L_BLUE", "L_GREEN", "L_YELLOW", "L_SIREN",
            // техобъект: режимы, операции, доступность, шаги
            "MODES", "OPERATIONS", "AVAILABILITY", "MODES_STEPS",
            // электрошкаф: состояние канала
            "ST_CH",
            // менеджер рецептов: номер, признак активности
            "NMR", "ACT", "LASTRECNMR",
            // система и узлы ввода-вывода
            "NODEENABLED", "CYCLE_TIME", "P_V_OFF_DELAY_TIME", "P_RESTRICTIONS_MODE",
            "P_RESTRICTIONS_MANUAL_TIME", "P_AUTO_PAUSE_OPER_ON_DEV_ERR",
            "WASH_VALVE_SEAT_PERIOD", "WASH_VALVE_UPPER_SEAT_TIME", "WASH_VALVE_LOWER_SEAT_TIME");

    private static final Set<String> STRING = Set.of(
            "CUR_PRG", "CUR_REC", "LOADED_REC", "PRG_LIST", "REC_LIST",
            // менеджер рецептов: имя рецепта и список
            "NAME", "LIST", "LASTRECNAME",
            // система
            "UP_TIME", "CMD_ANSWER", "VERSION");

    /**
     * Не default, а явный список: попадание сюда снимает пометку {@code guessed}, то есть
     * утверждение «тип проверен», а не «сошло по умолчанию». Параметры приборов ptusa хранит в
     * {@code par_float} и отдаёт дробными, даже когда в main.io.lua стоят круглые числа, — все
     * {@code P_*} прибора здесь.
     */
    private static final Set<String> FLOAT = Set.of(
            // измерения приборов
            "V", "F", "T", "FRQ", "RPM", "ABS_V", "CLEVEL",
            // счётчики расхода за сутки
            "DAY_T1", "DAY_T2", "PREV_DAY_T1", "PREV_DAY_T2",
            // электрошкаф: токи, напряжение, мощность
            "LOAD_CURRENT_CH", "NOMINAL_CURRENT_CH", "SUM_CURRENTS", "VOLTAGE", "OUT_POWER_90",
            // параметры техобъекта и рецепта
            "RT_PAR_F", "S_PAR_F", "PAR_MAIN", "PAR_SELFCLEAN", "REC_PAR", "PAR",
            // параметры приборов (par_float)
            "P_CZ", "P_DT", "P_ERR", "P_ERR_MIN_FLOW", "P_H_CONE", "P_MAX_FLOW", "P_MIN_FLOW",
            "P_MAX_F", "P_MIN_F", "P_MAX_P", "P_MAX_V", "P_MIN_V", "P_ON_TIME", "P_R", "P_C0",
            "P_FB", "P_T_GEN", "P_T_ERR");

    private DataTypeResolver() {
    }

    /** Поле вне таблицы — FLOAT с пометкой: тип правят параметром узла до выгрузки. */
    public static Resolved resolve(String field) {
        String base = INDEX.matcher(field).replaceAll("").trim();
        if (INT32.contains(base) || INT32_FAMILIES.stream().anyMatch(p -> p.matcher(base).matches())) {
            return new Resolved("INT32", false);
        }
        if (STRING.contains(base)) {
            return new Resolved("STRING", false);
        }
        return new Resolved("FLOAT", !FLOAT.contains(base));
    }
}
