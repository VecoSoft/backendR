-- V64: every public file GET now looks up the object's moderation rows (pending files are
-- uploader-only), not just the blocked ones — index all rows by object key.
CREATE INDEX idx_photo_moderation_object_key ON photo_moderation (object_key);
