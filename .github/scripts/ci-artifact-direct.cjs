// One bounded upload-action fallback, not a persistent Runner/Java proxy change.
for (const name of ['http_proxy', 'https_proxy', 'HTTP_PROXY', 'HTTPS_PROXY', 'all_proxy', 'ALL_PROXY']) {
  delete process.env[name]
}
