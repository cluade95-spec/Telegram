package org.telegram.messenger.localhistory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * Structural guard for B6: capture runs only at the remote entry points. Local deletions share
 * MessagesStorage.markMessagesAsDeletedInternal, so any capture call there (or anywhere unlisted) must fail this test.
 */
public class LocalHistoryCaptureWiringTest {

    private static File mainRoot() {
        for (String p : new String[]{"main/java", "src/main/java", "TMessagesProj/src/main/java"}) {
            File f = new File(p);
            if (f.isDirectory()) {
                return f;
            }
        }
        throw new IllegalStateException("cannot find main/java from " + new File(".").getAbsolutePath());
    }

    private static int count(String source, String needle) {
        int n = 0;
        for (int i = source.indexOf(needle); i >= 0; i = source.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    private static String read(String relative) throws Exception {
        return new String(Files.readAllBytes(new File(mainRoot(), relative).toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void captureCallsExistOnlyAtTheRemoteEntryPoints() throws Exception {
        String storage = read("org/telegram/messenger/MessagesStorage.java");
        String controller = read("org/telegram/messenger/MessagesController.java");
        assertEquals("edit capture inside putMessages", 1, count(storage, ".onEditStored("));
        assertEquals("no deletion capture in storage, local deletions share it", 0, count(storage, "onRemoteDeleteBeforeStorage"));
        assertEquals("update key 0 and delete push", 2, count(controller, ".onRemoteDeleteBeforeStorage("));
        assertEquals(0, count(controller, ".onEditStored("));
    }

    @Test
    public void noOtherFileCallsCapture() throws Exception {
        List<String> offenders = new ArrayList<>();
        try (Stream<java.nio.file.Path> files = Files.walk(mainRoot().toPath())) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                String name = p.getFileName().toString();
                if (name.equals("LocalHistoryCapture.java") || name.equals("MessagesStorage.java") || name.equals("MessagesController.java")) {
                    return;
                }
                try {
                    String s = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                    if (s.contains("onEditStored(") || s.contains("onRemoteDeleteBeforeStorage(")) {
                        offenders.add(name);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        assertTrue("unexpected capture callers: " + offenders, offenders.isEmpty());
    }

    @Test
    public void onlyTheServerEditUpdateSetsTheEditFlag() throws Exception {
        assertEquals(1, count(read("org/telegram/messenger/MessagesController.java"), "-2, 0, false, 0, 0, true)"));
    }
}
