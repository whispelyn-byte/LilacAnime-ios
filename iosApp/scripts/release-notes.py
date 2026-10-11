"""Select the authored changelog for a release tag, excluding older releases."""
import argparse
from pathlib import Path
import re


def release_notes(document, tag):
    match = re.fullmatch(r"v(\d+\.\d+(?:\.\d+)?)(?:-ios\.(\d+))?", tag)
    if not match:
        raise ValueError("Invalid release tag: " + tag)
    version, revision = match.groups()
    title = version + (" iOS 리비전 " + revision if revision else "")
    sections = re.split(r"(?m)^# ", document)
    for section in sections[1:]:
        heading, _, body = section.partition("\n")
        if heading.strip() == title:
            if not body.strip():
                raise ValueError("Empty changelog for " + tag)
            return "# " + section.strip() + "\n"
    raise ValueError("Write a changelog headed '# " + title + "' before publishing")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", required=True)
    parser.add_argument("--input", type=Path, default=Path(__file__).resolve().parents[2] / "docs/release-notes.md")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        notes = release_notes(args.input.read_text(encoding="utf-8"), args.tag)
    except ValueError as error:
        raise SystemExit(str(error))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(notes, encoding="utf-8")
