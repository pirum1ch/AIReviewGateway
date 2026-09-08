package com.review.gateway.service;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * GitLab Webhook Diff Trigger (plan §"Сборка и проверка диффа", threat model WHTB-ASSEMBLE): pure
 * transformer — GitLab's per-file JSON ({@link GitLabClient.DiffEntry}) → synthesized unified-diff text
 * ({@code diff --git a/{old} b/{new}}, mode/rename lines, {@code --- }/{@code +++ }, then the raw hunk
 * body GitLab returned). No DB/HTTP access, no config dependency — tested as an ordinary JUnit class,
 * without a Spring context.
 *
 * <p><b>Reject-before-assemble (§4.2 point 1, WHT-09):</b> this class never validates or repairs
 * anything — every entry it receives must already have passed {@link DiffIntegrityVerifier} (path
 * validation, hunk invariants, WHR-15/15b exemption filtering). Rendering an unvalidated {@code
 * old_path}/{@code new_path} here would forge a section delimiter in the assembled text (WHT-09); this
 * class has no way to know whether that happened, which is exactly why the check must run first, in a
 * different class, not here.
 *
 * <p><b>WHR-21 (the acceptance test that matters):</b> the true correctness bar for this class is not
 * "the output looks like a diff" — it is {@code DiffChunker.split(assemble(files), ...)} returning
 * {@code pathsTrusted() == true} and a file-path set exactly equal to {@code files}' own path set. See
 * {@code DiffAssemblerTest} for the table-driven fixtures asserting exactly that.
 */
@Service
public class DiffAssembler {

    /**
     * @param files entries already validated/filtered by {@link DiffIntegrityVerifier} — never raw,
     *              unvalidated GitLab JSON
     * @return the assembled unified-diff text, in {@code files} order
     */
    public String assemble(List<GitLabClient.DiffEntry> files) {
        StringBuilder sb = new StringBuilder();
        for (GitLabClient.DiffEntry file : files) {
            appendFile(sb, file);
        }
        return sb.toString();
    }

    private void appendFile(StringBuilder sb, GitLabClient.DiffEntry file) {
        sb.append("diff --git a/").append(file.oldPath()).append(" b/").append(file.newPath()).append('\n');

        boolean modeChanged = file.aMode() != null && file.bMode() != null && !file.aMode().equals(file.bMode());
        if (file.newFile()) {
            if (file.bMode() != null) {
                sb.append("new file mode ").append(file.bMode()).append('\n');
            }
        } else if (file.deletedFile()) {
            if (file.aMode() != null) {
                sb.append("deleted file mode ").append(file.aMode()).append('\n');
            }
        } else {
            if (file.renamedFile()) {
                sb.append("rename from ").append(file.oldPath()).append('\n');
                sb.append("rename to ").append(file.newPath()).append('\n');
            }
            if (modeChanged) {
                sb.append("old mode ").append(file.aMode()).append('\n');
                sb.append("new mode ").append(file.bMode()).append('\n');
            }
        }

        String oldSide = file.newFile() ? "/dev/null" : "a/" + file.oldPath();
        String newSide = file.deletedFile() ? "/dev/null" : "b/" + file.newPath();
        sb.append("--- ").append(oldSide).append('\n');
        sb.append("+++ ").append(newSide).append('\n');

        String diffBody = file.diff() == null ? "" : file.diff();
        if (!diffBody.isEmpty()) {
            sb.append(diffBody);
            if (!diffBody.endsWith("\n")) {
                sb.append('\n');
            }
        }
    }
}
