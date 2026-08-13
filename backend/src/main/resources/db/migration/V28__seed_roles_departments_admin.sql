-- roles seed
INSERT INTO roles (name, code, description, created_at, updated_at) VALUES
    ('일반 사용자',  'USER',             '기본 사용자 권한',      CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('관리자',      'ADMIN',            '시스템 전체 관리 권한',  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('문서 관리자', 'DOCUMENT_MANAGER', '문서 권한 관리 가능',    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- departments seed
INSERT INTO departments (name, code, description, status, created_at, updated_at) VALUES
    ('개발팀', 'DEV',     '개발 부서', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('인사팀', 'HR',      '인사 부서', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('재무팀', 'FINANCE', '재무 부서', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 최초 ADMIN 계정 (비밀번호: admin1234)
INSERT INTO users (email, password_hash, name, status, created_at, updated_at) VALUES
    ('kcw130502@gmail.com', '$2b$10$U60IG4W3G8pvipjwIww6iO9J1vuJnEpXLsAyaSJzlQOBCXAL9nnoS', '강철웅', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- ADMIN role 부여
INSERT INTO user_roles (user_id, role_id, assigned_by, assigned_at, created_at, updated_at) VALUES (
    (SELECT id FROM users WHERE email = 'kcw130502@gmail.com'),
    (SELECT id FROM roles WHERE code  = 'ADMIN'),
    (SELECT id FROM users WHERE email = 'kcw130502@gmail.com'),
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
);
