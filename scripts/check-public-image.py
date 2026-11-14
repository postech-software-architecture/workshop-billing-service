"""Verify anonymous GHCR pull access before any Kubernetes deployment."""
import argparse
import json
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def verify(image, opener=None):
    match = re.fullmatch(r'ghcr\.io/([a-z0-9][a-z0-9_.-]*/[a-z0-9][a-z0-9_.-]*):([0-9a-f]{40})', image)
    if not match:
        raise ValueError('Expected ghcr.io/organization/repository:<40-character commit SHA>.')
    repository, tag = match.groups()
    opener = opener or build_opener(NoRedirect())
    query = urlencode({'service': 'ghcr.io', 'scope': 'repository:' + repository + ':pull'})
    # Deliberately no login, Docker configuration or GitHub administrative token.
    with opener.open(Request('https://ghcr.io/token?' + query), timeout=20) as response:
        token = json.load(response).get('token')
    if not token:
        raise ValueError('Anonymous registry token unavailable.')
    request = Request('https://ghcr.io/v2/' + repository + '/manifests/' + tag,
                      headers={'Authorization': 'Bearer ' + token,
                               'Accept': 'application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.docker.distribution.manifest.v2+json'})
    with opener.open(request, timeout=20) as response:
        if response.status != 200:
            raise ValueError('Image manifest unavailable to anonymous clients.')
    return repository


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('image')
    args = parser.parse_args()
    try:
        verify(args.image)
    except (HTTPError, URLError, ValueError, OSError):
        print('Image is not anonymously accessible. Publish first with publish_only=true, '
              'then configure package visibility/access before deploying. '
              'No Kubernetes resources have been applied by this preflight.', file=sys.stderr)
        return 1
    print('Anonymous image pull access verified.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
