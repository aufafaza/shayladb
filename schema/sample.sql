-- Sample schema for mDBMS: the same tables as the built-in sample database (SM-14),
-- but created empty so a test or demo can insert its own rows.
-- Apply it while the server is stopped:
--   java -jar mdbms-app/target/mdbms.jar bootstrap schema/sample.sql --data-dir data
-- To get the tables with 50 sample rows each instead, skip the bootstrap and start the
-- server on an empty data directory.

CREATE TABLE students (
  nim INTEGER PRIMARY KEY,
  name CHAR(100) NOT NULL,
  gender CHAR(1) NOT NULL,
  major CHAR(100) NOT NULL,
  gpa FLOAT NOT NULL
);

CREATE TABLE courses (
  code VARCHAR(30) PRIMARY KEY,
  title VARCHAR(100) NOT NULL,
  credits INTEGER NOT NULL
);

CREATE TABLE takes (
  id INTEGER PRIMARY KEY,
  nim INTEGER NOT NULL,
  code VARCHAR(30) NOT NULL,
  grade INTEGER NOT NULL,
  semester INTEGER NOT NULL,
  FOREIGN KEY (nim) REFERENCES students(nim) ON DELETE RESTRICT ON UPDATE RESTRICT,
  FOREIGN KEY (code) REFERENCES courses(code) ON DELETE RESTRICT ON UPDATE RESTRICT
);

CREATE INDEX idx_takes_nim ON takes(nim);
CREATE INDEX idx_takes_code ON takes(code);
