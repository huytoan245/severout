from pathlib import Path

# Run the v2.2.2 source patch after correcting one validation-only literal.
# MainActivity keeps the count dynamic (${WisdomStore.count()}); therefore the
# source contains "lời khuyên", not a hard-coded "220 lời khuyên" string.
source_path = Path('ci-patches/v222_core_patch.py')
source = source_path.read_text(encoding='utf-8')
source = source.replace("'220 lời khuyên'", "'lời khuyên'")
exec(compile(source, str(source_path), 'exec'))
