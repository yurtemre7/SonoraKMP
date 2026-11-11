#!/usr/bin/env bash
# Fails if user-visible string literals are hardcoded in androidApp Kotlin sources (SPEC L10N-04).
# Ignores empty strings, format-only strings ("%d", "%s · %s"), URLs, identifier-like animation
# labels, @Preview functions, and lines marked `// l10n-ignore`.
set -euo pipefail
cd "$(dirname "$0")/.."
hits=$(find androidApp/src/main/kotlin -name '*.kt' -print0 | xargs -0 perl -ne '
  BEGIN { $lit = qr/"((?:[^"\\]|\\.)*)"/; }
  if (/^\s*\@Preview\b/) { $pv = 1 }
  if ($pv) { $pv = 0 if /^\}/; close ARGV if eof; next; }
  if (/\/\/\s*l10n-ignore/) { close ARGV if eof; next; }
  my $l = $_;
  my @c;
  while ($l =~ /\bText\(\s*$lit/g)                           { push @c, $1 }
  while ($l =~ /\blabel\s*=\s*$lit/g)                        { push @c, $1 if $1 =~ / / }
  while ($l =~ /\b(?:text|contentDescription|placeholder|title|subtitle|stateDescription|onClickLabel)\s*=\s*$lit/g) { push @c, $1 }
  while ($l =~ /\bshowSnackbar\(\s*(?:message\s*=\s*)?$lit/g) { push @c, $1 }
  while ($l =~ /\bToast\.makeText\([^,]+,\s*$lit/g)           { push @c, $1 }
  while ($l =~ /\bset(?:ContentTitle|ContentText|SubText|Ticker|TextViewText)\((?:[^,"]+,\s*)?$lit/g) { push @c, $1 }
  while ($l =~ /\b(?:NotificationChannel|addAction|CustomAction\.Builder)\([^"]*$lit/g) { push @c, $1 if $1 =~ / / }
  for my $s (@c) {
    next if $s eq "";
    next if $s =~ m{^[a-z][a-z0-9+.-]*://}i;
    my $t = $s; $t =~ s/%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z]//g; $t =~ s/\$\{[^}]*\}|\$\w+//g;
    next unless $t =~ /\p{L}{2,}/;
    print "$ARGV:$.: $s\n"; last;
  }
  close ARGV if eof;
')
if [ -n "$hits" ]; then
  echo "Hardcoded user-visible strings (move to res/values*/strings*.xml or mark // l10n-ignore):" >&2
  echo "$hits" >&2
  exit 1
fi
echo "Hardcoded string check passed."
