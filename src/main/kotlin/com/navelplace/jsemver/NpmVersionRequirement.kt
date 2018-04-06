package com.navelplace.jsemver

import com.navelplace.jsemver.antlr.NPMLexer
import com.navelplace.jsemver.antlr.NPMParser
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer


enum class Operator {
    EQ, GT, LT, GTEQ, LTEQ, TILDE, CARET;

    companion object {
        private val matches = mapOf(
                "=" to "EQ",
                ">" to "GT",
                "<" to "LT",
                "<=" to "LTEQ",
                ">=" to "GTEQ",
                "~" to "TILDE",
                "^" to "CARET")
        fun forString(value: String?): Operator? {
            if (value == null) return null
            return Operator.valueOf(matches.getValue(value.toUpperCase()))
        }
    }




}

interface Claus {
    fun isSatisfiedBy(version: Version): Boolean
}

abstract class Santa : Claus {

    companion object {
        fun clauseFor(context: NPMParser.OperatorClauseContext) : Claus{
            val operator = Operator.forString(context.operator()?.text?.toUpperCase())
            return when(operator) {
                null, Operator.EQ -> EqualClause(context)
                Operator.TILDE -> TildeClause(context)
                Operator.CARET -> CaretClause(context)
                Operator.LT -> LessThanClause(context)
                Operator.GTEQ -> GreaterThanEqualClause(context)
                Operator.GT -> GreaterThanClause(context)
                Operator.LTEQ -> LessThanEqualClause(context)
            }
        }
    }
    val range: VersionRange

    constructor(context: NPMParser.OperatorClauseContext) {
        this.range = rangeFor(context.version())
    }

    protected abstract fun rangeFor(versionContext: NPMParser.VersionContext): VersionRange

    protected open fun minFor(versionContext: NPMParser.VersionContext): Version {
        val preRelease = versionContext.preRelease()?.dottedLegal()?.legalCharacters()?.map { it.text }?.toTypedArray()
        val build = versionContext.build()?.dottedLegal()?.legalCharacters()?.map { it.text }?.toTypedArray()
        return NpmVersionRequirement.minFor(versionContext.major().text, versionContext.minor()?.text, versionContext.patch()?.text, preRelease, build)
    }

    protected open fun maxFor(versionContext: NPMParser.VersionContext): Version {
        val preRelease = versionContext.preRelease()?.dottedLegal()?.legalCharacters()?.map { it.text }?.toTypedArray()
        val build = versionContext.build()?.dottedLegal()?.legalCharacters()?.map { it.text }?.toTypedArray()
        return NpmVersionRequirement.maxFor(versionContext.major().text, versionContext.minor()?.text, versionContext.patch()?.text, preRelease, build)
    }

    override fun isSatisfiedBy(version: Version): Boolean {
        return range.contains(version)
    }
}

class EqualClause(context: NPMParser.OperatorClauseContext) : Santa(context) {
    override fun rangeFor(versionContext: NPMParser.VersionContext): VersionRange {
        return VersionRange(min = minFor(versionContext), max=maxFor(versionContext), minInclusive = true, maxInclusive = false)
    }
}

class GreaterThanClause(context: NPMParser.OperatorClauseContext) : Santa(context) {
    override fun rangeFor(versionContext: NPMParser.VersionContext): VersionRange {
        return VersionRange(min = minFor(versionContext), max=Version.MAX_VERSION, minInclusive = false, maxInclusive = true)
    }
}

class LessThanClause(context: NPMParser.OperatorClauseContext) : Santa(context) {
    override fun rangeFor(versionContext: NPMParser.VersionContext): VersionRange {
        return VersionRange(min = Version.MIN_VERSION, max=minFor(versionContext), minInclusive = true, maxInclusive = false)
    }
}

class GreaterThanEqualClause(context: NPMParser.OperatorClauseContext) : Santa(context) {
    override fun rangeFor(versionContext: NPMParser.VersionContext): VersionRange {
        return VersionRange(min = minFor(versionContext), max=Version.MAX_VERSION, minInclusive = true, maxInclusive = true)
    }
}

class LessThanEqualClause(context: NPMParser.OperatorClauseContext) : Santa(context) {
    override fun rangeFor(versionContext: NPMParser.VersionContext): VersionRange {
        return VersionRange(min = Version.MIN_VERSION, max=minFor(versionContext), minInclusive = true, maxInclusive = true)
    }
}

class TildeClause(context: NPMParser.OperatorClauseContext) : Santa(context) {
    override fun rangeFor(versionContext: NPMParser.VersionContext): VersionRange {
        return VersionRange(min = Version.MIN_VERSION, max=minFor(versionContext), minInclusive = true, maxInclusive = true)
    }
}

class CaretClause(context: NPMParser.OperatorClauseContext) : Santa(context) {
    override fun rangeFor(versionContext: NPMParser.VersionContext): VersionRange {
        return VersionRange(min = minFor(versionContext), max=maxFor(versionContext), minInclusive = true, maxInclusive = false)
    }

    override fun isSatisfiedBy(otherVersion: Version) =
        if (otherVersion.preRelease.isNotBlank()) {
            /*
            ^1.2.3-beta.2 := >=1.2.3-beta.2 <2.0.0
            Note that prereleases in the 1.2.3 version will be allowed, if they are greater than or equal to beta.2.
            So, 1.2.3-beta.4 would be allowed, but 1.2.4-beta.2 would not, because it is a prerelease of a different
            [major, minor, patch] tuple.

            ^0.0.3-beta := >=0.0.3-beta <0.0.4
            Note that prereleases in the 0.0.3 version only will be allowed, if they are greater than or equal to beta.
            So, 0.0.3-pr.2 would be allowed.
            */
            otherVersion.major == range.min.major &&
            otherVersion.minor == range.min.minor &&
            otherVersion.patch == range.min.patch &&
            range.contains(otherVersion)

        } else {
            range.contains(otherVersion)
        }

    override fun maxFor(versionContext: NPMParser.VersionContext): Version {
        var major = versionContext.major().text.toInt()
        val minorWildcard = NpmVersionRequirement.isWildcard(versionContext.minor()?.text)
        val patchWildcard = NpmVersionRequirement.isWildcard(versionContext.patch()?.text)
        var minor = if (minorWildcard) 0 else versionContext.minor()!!.text.toInt()
        var patch = if (patchWildcard) 0 else versionContext.patch()!!.text.toInt()
        if (major + minor + patch == 0 && minorWildcard) {
            // ^0.x := >=0.0.0 <1.0.0
            major = 1
        } else if (major + minor + patch == 0 &&  patchWildcard && !minorWildcard) {
            // ^0.0.x := >=0.0.0 <0.1.0
            // ^0.0 := >=0.0.0 <0.1.0
            patch = 0
            minor = 1
        } else if (major > 0) {
            // ^1.2.x := >=1.2.0 <2.0.0
            // ^1.2.3 := >=1.2.3 <2.0.0
            // ^2.3.4 := >=2.3.4 <3.0.0
            // ^1.2.3-beta.2 := >=1.2.3-beta.2 <2.0.0
            // ^1.x := >=1.0.0 <2.0.0
            major += 1
            minor = 0
            patch = 0
        } else if (major == 0 && minor == 0 && !patchWildcard && !minorWildcard) {
            // ^0.0.3 := >=0.0.3 <0.0.4
            patch += 1 // This has effectively become an equals clause
        } else if (major == 0 && !patchWildcard && !minorWildcard) {
            // ^0.2.3 := >=0.2.3 <0.3.0
            minor += 1
            patch = 0
        } else  if (major == 0 && minor == 0 && patch > 0) {
            // ^0.0.3 := >=0.0.3 <0.0.4
            patch += 1
        }
        return Version(major, minor, patch)
    }
}


class DashClaus : Claus {
    val versionRange: VersionRange

    constructor(context: NPMParser.DashClauseContext) {
        val min = context.version(0)
        val max = context.version(1)

        val preReleaseMin = min.preRelease().dottedLegal().legalCharacters().map { it.text }.toTypedArray()
        val preReleaseMax = max.preRelease().dottedLegal().legalCharacters().map { it.text }.toTypedArray()
        val buildMin = min.build().dottedLegal().legalCharacters().map { it.text }.toTypedArray()
        val buildMax = max.build().dottedLegal().legalCharacters().map { it.text }.toTypedArray()

        val minVersion = NpmVersionRequirement.minFor(min.major().text, min.minor().text, min.patch().text,preReleaseMin, buildMin)
        val maxVersion = NpmVersionRequirement.maxFor(max.major().text, min.minor().text, min.patch().text, preReleaseMax, buildMax)
        this.versionRange = VersionRange(min = minVersion, max = maxVersion)
    }

    override fun isSatisfiedBy(version: Version) = versionRange.contains(version)
}

class AndClaus(val predicates: Collection<Claus>): Claus {
    override fun isSatisfiedBy(version: Version) = predicates.all { it.isSatisfiedBy(version) }
}

class OrClaus(val predicates: Collection<Claus>): Claus {
    override fun isSatisfiedBy(version: Version) = predicates.any { it.isSatisfiedBy(version) }
}

class NpmVersionRequirement : VersionRequirement {

    companion object {
        fun isWildcard(value: String?): Boolean {
            return value == null ||
                    value.isNullOrBlank() ||
                    value.toUpperCase() == "X"
        }

        private fun validate(major: String, minor: String?, patch: String?, preRelease: Array<String>? = null, build: Array<String>? = null) {
            val preReleaseArray = preRelease ?: emptyArray()
            val buildArray = build ?: emptyArray()
            if (isWildcard(minor) && !isWildcard(patch)) {
                throw InvalidRequirementFormatException("$major.$minor.$patch")
            }
            if (
                    (isWildcard(minor) || isWildcard(patch)) &&
                    (preReleaseArray.isNotEmpty() || buildArray.isNotEmpty())
            ) {
                val pre = preReleaseArray.joinToString(".")
                val b = buildArray.joinToString(".")
                //TODO: This might not format the error correctly if pre or b are empty
                throw InvalidRequirementFormatException("$major.$minor.$patch-$pre+$b")
            }
        }
        internal fun maxFor(major: String, minor: String?, patch: String?, preRelease: Array<String>? = null, build: Array<String>? = null): Version {
            var majorInt = major.toInt()
            var minorInt = if (minor?.toUpperCase() == "X" || minor.isNullOrBlank()) 0 else minor!!.toInt()
            var patchInt = if (patch?.toUpperCase() == "X" || patch.isNullOrBlank()) 0 else patch!!.toInt()

            var preReleaseArray = preRelease ?: emptyArray()
            var buildArray = build ?: emptyArray()
            if (isWildcard(minor) || isWildcard(patch)) {
                preReleaseArray = emptyArray()
                buildArray = emptyArray()
            }




            if (!isWildcard(patch) && isWildcard(minor)) {
                throw InvalidRequirementFormatException("$major.$minor.$patch")
            }
            if (isWildcard(patch) && isWildcard(minor)) {
                majorInt++
            }
            if (isWildcard(patch) && !isWildcard(minor)) {
                minorInt++
            }

            validate(major, minor, patch, preRelease, build)
            return Version(majorInt, minorInt, patchInt, preReleaseArray, buildArray)
        }

        internal fun minFor(major: String, minor: String?, patch: String?, preRelease: Array<String>? = null, build: Array<String>? = null): Version {
            val majorInt = major.toInt()
            val minorInt = if (minor?.toUpperCase() == "X" || minor.isNullOrBlank()) 0 else minor!!.toInt()
            val patchInt = if (patch?.toUpperCase() == "X" || patch.isNullOrBlank()) 0 else patch!!.toInt()
            var preReleaseArray = preRelease ?: emptyArray()
            var buildArray = build ?: emptyArray()
            if (isWildcard(minor) || isWildcard(patch)) {
                preReleaseArray = emptyArray()
                buildArray = emptyArray()
            }


            if (patch != "X" && minor == "X") {
                throw InvalidRequirementFormatException("$major.$minor.$patch")
            }
            validate(major, minor, patch, preRelease, build)
            return Version(majorInt, minorInt, patchInt, preReleaseArray, buildArray)
        }
    }

    val orClaus: Claus

    constructor(rawRequirement: String): super(rawRequirement, RequirementType.NPM) {
        val lexer = NPMLexer(CharStreams.fromString(rawRequirement))
        val parser = NPMParser(CommonTokenStream(lexer))
        val listener = ThrowingRequirementErrorListener(rawRequirement)
        lexer.addErrorListener(listener)
        parser.addErrorListener(listener)

        val intersections = parser.union().intersection()
        val size = intersections.size
        val size2 = intersections[0].operatorClause().size
        val op = intersections[0].operatorClause()[0]
        this.orClaus = OrClaus(intersections.map { intersection ->
            val operatorClauses = intersection.operatorClause().map { Santa.clauseFor(it) }
            val dashClauses = intersection.dashClause().map { DashClaus(it) }
            val clauses = ArrayList<Claus>()
            clauses.addAll(operatorClauses)
            clauses.addAll(dashClauses)
            AndClaus(clauses)
        })
    }

    override fun isSatisfiedBy(version: Version) = orClaus.isSatisfiedBy(version)
    override fun calculate(rawRequirement: String) = emptyArray<VersionRange>()



}

/**
 * @suppress
 */
class ThrowingRequirementErrorListener(val input: String) : BaseErrorListener() {
    override fun syntaxError(recognizer: Recognizer<*, *>, offendingSymbol: Any?, line: Int, charPositionInLine: Int, msg: String, e: RecognitionException?) {
        throw InvalidRequirementFormatException(input)
    }
}