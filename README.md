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
