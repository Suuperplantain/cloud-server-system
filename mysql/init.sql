CREATE DATABASE IF NOT EXISTS cloud;
USE cloud;

-- Users
CREATE TABLE IF NOT EXISTS users (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(64) NOT NULL UNIQUE,
  pass_hash VARCHAR(256) NOT NULL,
  role ENUM('STANDARD','ADMIN') NOT NULL DEFAULT 'STANDARD',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Files metadata
CREATE TABLE IF NOT EXISTS files (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  owner VARCHAR(64) NOT NULL,
  filename VARCHAR(255) NOT NULL,
  storage_node VARCHAR(32) NOT NULL,
  size_bytes BIGINT NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uniq_owner_file (owner, filename),
  INDEX idx_owner (owner),
  CONSTRAINT fk_files_owner FOREIGN KEY (owner) REFERENCES users(username)
    ON DELETE CASCADE ON UPDATE CASCADE
);

-- Access Control List
CREATE TABLE IF NOT EXISTS acl (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  owner VARCHAR(64) NOT NULL,
  filename VARCHAR(255) NOT NULL,
  grantee VARCHAR(64) NOT NULL,
  can_read BOOLEAN NOT NULL DEFAULT TRUE,
  can_write BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uniq_acl (owner, filename, grantee),
  INDEX idx_grantee (grantee),
  CONSTRAINT fk_acl_owner_file FOREIGN KEY (owner, filename)
    REFERENCES files(owner, filename)
    ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT fk_acl_grantee FOREIGN KEY (grantee)
    REFERENCES users(username)
    ON DELETE CASCADE ON UPDATE CASCADE
);

-- Audit log
CREATE TABLE IF NOT EXISTS audit_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(64),
  action VARCHAR(64) NOT NULL,
  details TEXT,
  storage_node VARCHAR(32),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_user_time (username, created_at)
);
