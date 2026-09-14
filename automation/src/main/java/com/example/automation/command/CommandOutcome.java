package com.example.automation.command;

/**
 * Исход команды записи — то, что произошло в ПЛК. Статусы шлюза (APPLIED, REJECTED_*, FAILED_*)
 * плюс локальные NOT_DELIVERED и NO_CONFIRMATION («исход неизвестен»).
 */
public record CommandOutcome(boolean applied, String status, String message) {

    public static CommandOutcome failure(String status, String message) {
        return new CommandOutcome(false, status, message);
    }
}
