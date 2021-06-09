/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:kotlin.jvm.JvmMultifileClass
@file:kotlin.jvm.JvmName("StringsKt")

package kotlin.text

/**
 * Converts the string into a regular expression [Regex] with the default options.
 */
@kotlin.internal.InlineOnly
public inline fun String.toRegex(): Regex = Regex(this)

/**
 * Converts the string into a regular expression [Regex] with the specified single [option].
 */
@kotlin.internal.InlineOnly
public inline fun String.toRegex(option: RegexOption): Regex = Regex(this, option)

/**
 * Converts the string into a regular expression [Regex] with the specified set of [options].
 */
@kotlin.internal.InlineOnly
public inline fun String.toRegex(options: Set<RegexOption>): Regex = Regex(this, options)

/**
 * Converts the glob string into a regular expression [Regex] with the default options.
 */
public fun String.toRegexFromGlob(separator:Char): Regex = this.toRegexFromGlob(separator, emptySet())


/*
 * Modified from code given in [https://stackoverflow.com/questions/1247772/is-there-an-equivalent-of-java-util-regex-for-glob-type-patterns]
 * ("I hereby grant that the code in this answer is in the public domain. – Neil Traft Apr 17 at 18:49")
 *
 * Converts a standard POSIX Shell globbing pattern into a regular expression
 * pattern. The result can be used with the standard {@link java.util.regex} API to
 * recognize strings which match the glob pattern.
 * <p/>
 * See also, the POSIX Shell language:
 * http://pubs.opengroup.org/onlinepubs/009695399/utilities/xcu_chap02.html#tag_02_13_01
 *
 * original author Neil Traft
 *
 */
/**
 * Converts the glob string into a regular expression [Regex] with the specified set of [options].
 */
public fun String.toRegexFromGlob(separator: Char, options: Set<RegexOption>): Regex {
    val globStr = this
    var regex = ""
    var inGroup = 0
    var inCharacterClass = 0
    var firstIndexInClass = -1

    var i = 0
    while (i < globStr.length) {
        val ch = globStr[i]
        when (ch) {
            '\\' -> {
                if (++i >= globStr.length) {
                    regex += '\\'
                } else {
                    val next = globStr[i]
                    when (next) {
                        ',' -> Unit /* escape not needed */
                        'Q', 'E' ->  regex += "\\\\" /* extra escape needed */
                        else -> regex += '\\'
                    }
                    regex += next
                }
            }
            '*' -> {
                if (inCharacterClass == 0) {
                    if (i + 1 >= globStr.length) {
                        regex += "[^$separator]*"
                    } else {
                        val next = globStr[i + 1]
                        when (next) {
                            '*' -> {
                                regex += ".*"
                                i++
                            }
                            else -> regex += "[^$separator]*"
                        }
                    }
                } else {
                    regex += '*'
                }
            }
            '?' -> regex += if (inCharacterClass == 0) "([^$separator])" else '?'
            '[' -> {
                inCharacterClass++
                firstIndexInClass = i + 1
                regex += '['
            }
            ']' -> {
                inCharacterClass--
                regex += "&&[^$separator]]"
            }
            '{' -> {
                inGroup++
                regex += '('
            }
            '}' -> {
                inGroup--
                regex += ')'
            }
            '!' -> regex += if (firstIndexInClass == i) '^' else '!'
            ',' -> regex += if (inGroup > 0) '|' else ','
            '.', '(', ')', '+', '|', '^', '$', '@', '%' -> {
                if (inCharacterClass == 0 || (firstIndexInClass == i && ch == '^')) regex += '\\'
                regex += ch
            }
            else -> regex += ch
        }
        i++
    }

    return Regex(regex, options)
}