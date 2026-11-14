import importlib.util
import io
import json
from pathlib import Path
import unittest
from unittest.mock import MagicMock
from urllib.error import HTTPError

spec = importlib.util.spec_from_file_location('public_image', Path(__file__).with_name('check-public-image.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
IMAGE = 'ghcr.io/postech-software-architecture/workshop-os-service:' + 'a' * 40


class PublicImageTest(unittest.TestCase):
    def opener(self):
        opener = MagicMock()
        token_response = io.StringIO(json.dumps({'token': 'anonymous-scoped-token'}))
        manifest_response = MagicMock()
        manifest_response.__enter__.return_value.status = 200
        opener.open.side_effect = [token_response, manifest_response]
        return opener

    def test_public_image_uses_only_anonymous_scoped_token(self):
        opener = self.opener()
        self.assertEqual('postech-software-architecture/workshop-os-service', module.verify(IMAGE, opener))
        token_request = opener.open.call_args_list[0].args[0]
        self.assertIsNone(token_request.get_header('Authorization'))
        self.assertIn('scope=repository%3Apostech-software-architecture%2Fworkshop-os-service%3Apull', token_request.full_url)
        manifest_request = opener.open.call_args_list[1].args[0]
        self.assertEqual('Bearer anonymous-scoped-token', manifest_request.get_header('Authorization'))
        self.assertEqual('ghcr.io', manifest_request.host)

    def test_private_image_stops_without_manifest_request(self):
        opener = MagicMock()
        opener.open.side_effect = HTTPError('https://ghcr.io/token', 403, 'Forbidden', {}, None)
        with self.assertRaises(HTTPError):
            module.verify(IMAGE, opener)
        self.assertEqual(1, opener.open.call_count)

    def test_other_hosts_credentials_and_mutable_tags_are_rejected_before_network(self):
        for image in [IMAGE.replace('ghcr.io', 'other.example'), IMAGE.replace('ghcr.io/', 'ghcr.io/user:password@'),
                      IMAGE.rsplit(':', 1)[0] + ':latest', IMAGE + '?token=private']:
            opener = MagicMock()
            with self.subTest(image=image), self.assertRaises(ValueError):
                module.verify(image, opener)
            opener.open.assert_not_called()

    def test_missing_anonymous_token_stops_before_manifest(self):
        opener = MagicMock()
        opener.open.return_value = io.StringIO('{}')
        with self.assertRaises(ValueError):
            module.verify(IMAGE, opener)
        self.assertEqual(1, opener.open.call_count)

    def test_redirects_are_not_followed(self):
        self.assertIsNone(module.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://other.example'))


if __name__ == '__main__':
    unittest.main()
