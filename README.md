# xfa2acro-repair

Convert and repair XFA PDFs into AcroForm PDFs using Aspose Cloud + PDFBox.

## Installation

```bash
curl -fsSL https://raw.githubusercontent.com/reyx/xfa2acro-repair/main/install.sh | bash
```

## Usage

```bash
xfa2acro-repair ~/Downloads/g-1450.pdf
# -> ~/Downloads/g-1450.safe.pdf
```

## Release

```bash
brew install gh
gh auth login
```

### pull latest

```bash
git checkout main
git pull --rebase
```

### create a release branch (optional but tidy)

```bash
export VER=1.0.0
git checkout -b release/v$VER
```

### bump version in pom.xml (or wherever you store version)

### do it however you prefer; example using Maven Versions Plugin:

```bash
mvn -q versions:set -DnewVersion=$VER
mvn -q versions:commit
```

### build

```bash
mvn -B -DskipTests package
```

### commit version bump

```bash
git add -A
git commit -m "chore(release): v$VER"
```

### push

```bash
git tag -a v$VER -m "v$VER"
git push origin release/v$VER
git push origin v$VER
```

### create release

```bash
ARTIFACT="target/xfa2acro-repair-$VER.jar"

# auto-generate release notes and attach the artifact
gh release create "v$VER" "$ARTIFACT" \
  --target main \
  --title "v$VER" \
  --generate-notes

git fetch origin
git checkout main
git pull --rebase

# fast-forward main to the release branch (no merge commit)
git merge --ff-only origin/release/v$VER

# push updated main
git push origin main
```
