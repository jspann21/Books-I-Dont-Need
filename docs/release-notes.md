# Books I Don't Need 1.0.7

## Changelog

- Fixed eBay imports and price updates by retaining the session cookies eBay issues during its homepage bootstrap response.
- Stopped eBay requests from continuing with an empty session when session establishment genuinely fails.
- Added retries and clearer user-facing handling for interrupted retailer connections.
- Prevented expected Android background foreground-service restrictions from being reported as application errors.
