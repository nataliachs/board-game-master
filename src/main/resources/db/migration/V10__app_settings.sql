-- Settings an admin can change while the application is running, without a new deployment.
-- A missing row means "use the configured default" (environment variable or application.yaml).
CREATE TABLE app_settings (
    setting_key VARCHAR(100) PRIMARY KEY CHECK (setting_key ~ '^[a-z0-9.-]+$'),
    setting_value VARCHAR(200) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
