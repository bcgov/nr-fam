
ALTER TABLE app_fam.fam_application_admin
    ALTER COLUMN create_user SET DATA TYPE VARCHAR(60),
    ALTER COLUMN update_user SET DATA TYPE VARCHAR(60);
