-- Portal sign-in uses the access token alone; refresh tokens were issued at login but never read.

DROP TABLE IF EXISTS refresh_tokens;
