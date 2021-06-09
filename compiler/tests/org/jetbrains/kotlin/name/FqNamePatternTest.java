/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.name;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.List;


@RunWith(Parameterized.class)
public class FqNamePatternTest {

    static class TestData {
        String pattern;
        String value;
        boolean shouldPass;

        public TestData(String pattern, String value, boolean shouldPass) {
            this.pattern = pattern;
            this.value = value;
            this.shouldPass = shouldPass;
        }

        @Override
        public String toString() {
            if (shouldPass) {
                return "'"+pattern + "' matches '" + value+"'";
            } else {
                return  "'"+pattern + "' NOT matches '" + value+"'";
            }
        }
    }

    @Parameterized.Parameters(name="{0}")
    public static List<TestData> data() {
        // glob pattern matching which underlies FqNamePattern is more thoroughly tested in GlobToRegexTest
        return Arrays.asList(
                new TestData("", "", true),
                new TestData("", "a.b.c", false),
                new TestData("a", "a.b.c", false),
                new TestData("a.b", "a.b", true),
                new TestData("a.b", "a.b.c", false),
                new TestData("a.b.c", "a.b", false),
                new TestData("a.b.c", "a.b.c", true),
                new TestData("?", "a.b.c", false),
                new TestData("a.?", "a.b", true),
                new TestData("a.?", "a.b.c", false),
                new TestData("*", "abc", true),
                new TestData("*", "abc.bcd.cde", false),
                new TestData("abc.*", "abc.bcd", true),
                new TestData("abc.*", "abc.bcd.cde", false),
                new TestData("abc.bcd.*", "abc.bcd", false),
                new TestData("abc.bcd.*", "abc.bcd.cde", true)
        );
    }

    private TestData data;

    public FqNamePatternTest(TestData data) {
        this.data = data;
    }

    @Test
    public void test() {
        FqNamePattern fqNamePattern = new FqNamePattern(this.data.pattern);
        fqNamePattern.matches(new FqName(this.data.value));
    }
}
