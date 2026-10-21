"""Distribution checks use temporary synthetic fixtures, never actual credentials."""
import contextlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import prepare_github as release

class ReleaseTests(unittest.TestCase):
    def test_private_files_are_not_in_archive(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for name in ['.env', '.env.backup', 'server.key', '.tools/private.txt',
                         'uploads/clip.wav', 'api/target/app.jar', '.git/config']:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('synthetic private fixture')
            (root / '.env.example').write_text('DEMO_PASSWORD=local-demo-change-me')
            (root / 'README.md').write_text('Synthetic release fixture')
            with patch.object(release, 'ROOT', root), patch('sys.argv', ['scan', '--zip']), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, release.main())
            with zipfile.ZipFile(root / 'github-release.zip') as archive:
                self.assertEqual({'.env.example', 'README.md'}, set(archive.namelist()))

    def test_copied_environment_value_blocks_release_without_printing_value(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            secret = 'synthetic-private-credential'
            (root / '.env').write_text('DEMO_PASSWORD=' + secret)
            (root / 'bad.txt').write_text(secret)
            output = io.StringIO()
            with patch.object(release, 'ROOT', root), patch('sys.argv', ['scan', '--zip']), contextlib.redirect_stdout(output):
                self.assertEqual(1, release.main())
            self.assertNotIn(secret, output.getvalue())
            self.assertFalse((root / 'github-release.zip').exists())

if __name__ == '__main__':
    unittest.main()
