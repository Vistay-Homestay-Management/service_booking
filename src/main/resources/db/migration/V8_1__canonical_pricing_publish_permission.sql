SET search_path TO app, extensions, public;

ALTER TABLE pricing_profile_publications
    DROP CONSTRAINT ck_pricing_publication_authorization;

UPDATE pricing_profile_publications
SET permission_code = 'pricing:publish'
WHERE permission_code = 'pricing:approve';

ALTER TABLE pricing_profile_publications
    ADD CONSTRAINT ck_pricing_publication_authorization CHECK (
        authorization_version >= 1
        AND permission_code = 'pricing:publish'
        AND (
            (scope_type = 'ALL' AND partition_id IS NULL)
            OR (scope_type = 'BRANCH' AND partition_id IS NOT NULL)
        )
    );
