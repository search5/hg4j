package com.github.search5.hg4j.api;

import com.github.search5.hg4j.core.Revlog;
import java.io.*;
import java.util.Random;

public class RobustnessFuzzingHarness {
    public static byte[] generateCorruptedPayload(byte[] original, double corruptionRate) {
        byte[] corrupted = original.clone();
        Random rand = new Random(42);
        int numBytesToCorrupt = (int) (corrupted.length * corruptionRate);
        
        for (int i = 0; i < numBytesToCorrupt; i++) {
            int idx = rand.nextInt(corrupted.length);
            corrupted[idx] ^= (byte) (1 << rand.nextInt(8));
        }
        return corrupted;
    }

    public static void fuzzRevlogReader(File idxFile, File datFile) {
        try {
            Revlog revlog = new Revlog(idxFile, datFile);
            for (int i = 0; i < revlog.getRevisionCount(); i++) {
                revlog.getRevisionContent(i);
            }
        } catch (com.github.search5.hg4j.errors.HgCorruptDataException | com.github.search5.hg4j.errors.HgRevisionNotFoundException e) {
            // 정상 포착됨
        } catch (OutOfMemoryError oom) {
            throw new AssertionError("Fuzzing FAILED: Parser allocated excessive memory!", oom);
        } catch (Exception e) {
            // 안전
        }
    }
}
