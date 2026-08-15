package com.example.editor.exception;

import com.example.editor.merge.MergeConflict;
import lombok.Getter;

import java.util.List;

/**
 * Слияние не сошлось. Форма ответа — та же, что у {@link VersionMismatchException}, плюс список
 * расхождений: контракт обещает фронту, что обработчик {@code 409} у него один на оба случая.
 */
@Getter
public class MergeConflictException extends RuntimeException {

    private final Integer baseVersion;
    private final Integer currentVersion;
    private final transient List<MergeConflict> conflicts;
    private final String theirUser;

    public MergeConflictException(Integer baseVersion, Integer currentVersion,
                                  List<MergeConflict> conflicts, String theirUser) {
        super("Merge conflict: " + conflicts.size() + " entity(ies) changed on both sides");
        this.baseVersion = baseVersion;
        this.currentVersion = currentVersion;
        this.conflicts = conflicts;
        this.theirUser = theirUser;
    }
}
