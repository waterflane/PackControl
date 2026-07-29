package org.wodichka.packcontrol.updateformat;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict SemVer 2.0.0 value. Build metadata is retained but does not affect
 * precedence, as required by the SemVer specification.
 */
public final class SemanticVersion implements Comparable<SemanticVersion> {
    private static final Pattern PATTERN = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                    + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
                    + "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$"
    );

    private final BigInteger major;
    private final BigInteger minor;
    private final BigInteger patch;
    private final List<Identifier> prerelease;
    private final String build;
    private final String text;

    private SemanticVersion(
            BigInteger major,
            BigInteger minor,
            BigInteger patch,
            List<Identifier> prerelease,
            String build,
            String text
    ) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.prerelease = List.copyOf(prerelease);
        this.build = build;
        this.text = text;
    }

    public static SemanticVersion parse(String value) {
        Objects.requireNonNull(value, "value");
        Matcher matcher = PATTERN.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid semantic version: " + value);
        }
        List<Identifier> prerelease = new ArrayList<>();
        if (matcher.group(4) != null) {
            for (String valuePart : matcher.group(4).split("\\.")) {
                boolean numeric = valuePart.chars().allMatch(Character::isDigit);
                if (numeric && valuePart.length() > 1 && valuePart.startsWith("0")) {
                    throw new IllegalArgumentException(
                            "Numeric prerelease identifiers must not contain leading zeroes: " + value
                    );
                }
                prerelease.add(new Identifier(valuePart, numeric));
            }
        }
        return new SemanticVersion(
                new BigInteger(matcher.group(1)),
                new BigInteger(matcher.group(2)),
                new BigInteger(matcher.group(3)),
                prerelease,
                matcher.group(5),
                value
        );
    }

    public static Optional<SemanticVersion> tryParse(String value) {
        try {
            return Optional.of(parse(value));
        } catch (IllegalArgumentException | NullPointerException exception) {
            return Optional.empty();
        }
    }

    public static Optional<SemanticVersion> tryParseTag(String tag) {
        if (tag == null) {
            return Optional.empty();
        }
        String candidate = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
        return tryParse(candidate);
    }

    public boolean isPrerelease() {
        return !prerelease.isEmpty();
    }

    public String buildMetadata() {
        return build;
    }

    @Override
    public int compareTo(SemanticVersion other) {
        int core = major.compareTo(other.major);
        if (core == 0) {
            core = minor.compareTo(other.minor);
        }
        if (core == 0) {
            core = patch.compareTo(other.patch);
        }
        if (core != 0) {
            return core;
        }
        if (prerelease.isEmpty() && other.prerelease.isEmpty()) {
            return 0;
        }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return prerelease.isEmpty() ? 1 : -1;
        }
        int common = Math.min(prerelease.size(), other.prerelease.size());
        for (int index = 0; index < common; index++) {
            int compared = prerelease.get(index).compareTo(other.prerelease.get(index));
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(prerelease.size(), other.prerelease.size());
    }

    @Override
    public String toString() {
        return text;
    }

    private record Identifier(String text, boolean numeric) implements Comparable<Identifier> {
        @Override
        public int compareTo(Identifier other) {
            if (numeric && other.numeric) {
                return new BigInteger(text).compareTo(new BigInteger(other.text));
            }
            if (numeric != other.numeric) {
                return numeric ? -1 : 1;
            }
            return text.compareTo(other.text);
        }
    }
}
