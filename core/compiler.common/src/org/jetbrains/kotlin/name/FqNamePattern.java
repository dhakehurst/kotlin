/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.name;

import kotlin.text.Regex;
import kotlin.text.StringsKt;
import org.jetbrains.annotations.NotNull;

/**
 * A glob based pattern (with '.' as separator) used to match against an FqName
 */
public class FqNamePattern {

    private String globPattern;
    private Regex regexPattern;

    public FqNamePattern(@NotNull String globPattern) {
        this.globPattern = globPattern;
        this.regexPattern = StringsKt.toRegexFromGlob(globPattern,'.');
    }

    public boolean matches(FqName fqName) {
        return regexPattern.matches(fqName.asString());
    }

    @Override
    public String toString() {
        return "FqNamePattern{" +
               "globPattern='" + globPattern +
               "'}";
    }
}
