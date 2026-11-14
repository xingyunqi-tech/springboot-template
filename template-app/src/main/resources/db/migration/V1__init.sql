CREATE EXTENSION IF NOT EXISTS "uuid-ossp" WITH SCHEMA public;

CREATE TABLE public.account_cleanup (
    "userId" integer NOT NULL,
    "completedAt" timestamp with time zone NOT NULL
);

CREATE TABLE public.address (
    id integer NOT NULL,
    "userId" integer NOT NULL,
    name character varying(50) NOT NULL,
    phone character varying(20) NOT NULL,
    province character varying(50) NOT NULL,
    city character varying(50) NOT NULL,
    district character varying(50) NOT NULL,
    address character varying(200) NOT NULL,
    "isDefault" boolean DEFAULT false NOT NULL,
    "createdAt" timestamp without time zone DEFAULT now() NOT NULL,
    "updatedAt" timestamp without time zone DEFAULT now() NOT NULL,
    "deletedAt" timestamp without time zone,
    "provinceCode" character varying(12),
    "cityCode" character varying(12),
    "districtCode" character varying(12)
);

COMMENT ON COLUMN public.address."provinceCode" IS 'Province region code';

COMMENT ON COLUMN public.address."cityCode" IS 'City region code';

COMMENT ON COLUMN public.address."districtCode" IS 'District region code';

CREATE SEQUENCE public.address_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.address_id_seq OWNED BY public.address.id;

CREATE TABLE public.admin_alert (
    id integer NOT NULL,
    type character varying(50) NOT NULL,
    level character varying(20) NOT NULL,
    message text NOT NULL,
    payload jsonb,
    "dedupKey" character varying(200) NOT NULL,
    "acknowledgedAt" timestamp without time zone,
    "createdAt" timestamp without time zone DEFAULT now() NOT NULL,
    "updatedAt" timestamp without time zone DEFAULT now() NOT NULL
);

CREATE SEQUENCE public.admin_alert_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.admin_alert_id_seq OWNED BY public.admin_alert.id;

CREATE TABLE public.audit_logs (
    id integer NOT NULL,
    "userId" character varying,
    action character varying(50) NOT NULL,
    "entityType" character varying(100) NOT NULL,
    "entityId" character varying NOT NULL,
    "oldValue" jsonb,
    "newValue" jsonb,
    "ipAddress" character varying,
    "userAgent" text,
    "requestId" character varying,
    "createdAt" timestamp without time zone DEFAULT now() NOT NULL
);

CREATE SEQUENCE public.audit_logs_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.audit_logs_id_seq OWNED BY public.audit_logs.id;

CREATE TABLE public.config (
    key character varying(200) NOT NULL,
    value jsonb NOT NULL,
    "createdAt" timestamp without time zone DEFAULT now() NOT NULL,
    "updatedAt" timestamp without time zone DEFAULT now() NOT NULL,
    "isPublic" boolean DEFAULT false NOT NULL
);

CREATE TABLE public.favorite (
    id integer NOT NULL,
    "userId" integer NOT NULL,
    "targetType" character varying(20) NOT NULL,
    "targetId" character varying(100) NOT NULL,
    title character varying(200),
    image character varying(500),
    extra text,
    "createdAt" timestamp without time zone DEFAULT now() NOT NULL,
    "subjectId" uuid
);

CREATE SEQUENCE public.favorite_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.favorite_id_seq OWNED BY public.favorite.id;

CREATE TABLE public.feedback (
    id integer NOT NULL,
    "userId" integer,
    type character varying(20) NOT NULL,
    content text NOT NULL,
    images jsonb,
    contact character varying(100),
    status character varying(20) DEFAULT 'pending'::character varying NOT NULL,
    reply text,
    "createdAt" timestamp without time zone DEFAULT now() NOT NULL,
    "updatedAt" timestamp without time zone DEFAULT now() NOT NULL,
    "questionId" uuid,
    "questionVersion" integer,
    reason character varying(20),
    "errataId" uuid,
    "messageId" uuid,
    "messageSnapshot" jsonb
);

CREATE SEQUENCE public.feedback_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.feedback_id_seq OWNED BY public.feedback.id;

CREATE TABLE public.file (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    path character varying NOT NULL,
    "ownerUserId" integer,
    visibility character varying(10) DEFAULT 'PUBLIC'::character varying NOT NULL,
    "mimeType" character varying(100),
    size integer,
    sha256 character varying(64)
);

CREATE TABLE public.notification (
    id integer NOT NULL,
    "userId" integer NOT NULL,
    type character varying(20) NOT NULL,
    category character varying(20) NOT NULL,
    title character varying(200) NOT NULL,
    content text NOT NULL,
    metadata jsonb,
    channels jsonb DEFAULT '[]'::jsonb NOT NULL,
    "sentChannels" jsonb DEFAULT '{}'::jsonb NOT NULL,
    "isRead" boolean DEFAULT false NOT NULL,
    "readAt" timestamp without time zone,
    "createdAt" timestamp without time zone DEFAULT now() NOT NULL,
    "updatedAt" timestamp without time zone DEFAULT now() NOT NULL,
    "deletedAt" timestamp without time zone
);

CREATE SEQUENCE public.notification_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.notification_id_seq OWNED BY public.notification.id;

CREATE TABLE public.notification_setting (
    id integer NOT NULL,
    "userId" integer NOT NULL,
    category character varying(20) NOT NULL,
    "enableInApp" boolean DEFAULT true NOT NULL,
    "enableEmail" boolean DEFAULT true NOT NULL,
    "enableSms" boolean DEFAULT true NOT NULL,
    "enablePush" boolean DEFAULT true NOT NULL,
    "createdAt" timestamp without time zone DEFAULT now() NOT NULL,
    "updatedAt" timestamp without time zone DEFAULT now() NOT NULL
);

CREATE SEQUENCE public.notification_setting_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.notification_setting_id_seq OWNED BY public.notification_setting.id;

CREATE TABLE public.permission (
    id character varying(50) NOT NULL,
    name character varying(100) NOT NULL,
    description character varying(255),
    resource character varying(50) NOT NULL,
    action character varying(50) NOT NULL,
    "createdAt" timestamp without time zone DEFAULT now() NOT NULL,
    "updatedAt" timestamp without time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.region (
    id integer NOT NULL,
    code character varying(12) NOT NULL,
    name character varying(50) NOT NULL,
    level integer NOT NULL,
    "parentCode" character varying(12),
    sort integer DEFAULT 0 NOT NULL,
    "createdAt" timestamp without time zone DEFAULT now() NOT NULL,
    "updatedAt" timestamp without time zone DEFAULT now() NOT NULL
);

COMMENT ON TABLE public.region IS 'Administrative division data (Province/City/District)';

CREATE SEQUENCE public.region_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.region_id_seq OWNED BY public.region.id;

CREATE TABLE public.role (
    id integer NOT NULL,
    name character varying NOT NULL
);

CREATE TABLE public.role_permission (
    role_id integer NOT NULL,
    permission_id character varying(50) NOT NULL,
    "createdAt" timestamp without time zone DEFAULT now() NOT NULL
);

CREATE TABLE public.session (
    id integer NOT NULL,
    hash character varying NOT NULL,
    "createdAt" timestamp without time zone DEFAULT now() NOT NULL,
    "updatedAt" timestamp without time zone DEFAULT now() NOT NULL,
    "deletedAt" timestamp without time zone,
    "userId" integer,
    "userAgent" character varying(512),
    ip character varying(64),
    "lastActiveAt" timestamp without time zone
);

CREATE SEQUENCE public.session_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.session_id_seq OWNED BY public.session.id;

CREATE TABLE public.status (
    id integer NOT NULL,
    name character varying NOT NULL
);

CREATE TABLE public."user" (
    id integer NOT NULL,
    email character varying,
    password character varying,
    provider character varying DEFAULT 'email'::character varying NOT NULL,
    "firstName" character varying,
    "lastName" character varying,
    "createdAt" timestamp without time zone DEFAULT now() NOT NULL,
    "updatedAt" timestamp without time zone DEFAULT now() NOT NULL,
    "deletedAt" timestamp without time zone,
    "photoId" uuid,
    "roleId" integer,
    "statusId" integer,
    phone character varying,
    nickname character varying,
    gender integer DEFAULT 0,
    birthday date,
    "wechatOpenId" character varying,
    "wechatUnionId" character varying,
    "alipayOpenId" character varying
);

CREATE TABLE public.user_consent (
    id uuid DEFAULT public.uuid_generate_v4() NOT NULL,
    "userId" integer NOT NULL,
    type character varying(20) NOT NULL,
    version character varying(50) NOT NULL,
    "agreedAt" timestamp without time zone DEFAULT now() NOT NULL,
    ip character varying(45)
);

CREATE SEQUENCE public.user_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.user_id_seq OWNED BY public."user".id;

ALTER TABLE ONLY public.address ALTER COLUMN id SET DEFAULT nextval('public.address_id_seq'::regclass);

ALTER TABLE ONLY public.admin_alert ALTER COLUMN id SET DEFAULT nextval('public.admin_alert_id_seq'::regclass);

ALTER TABLE ONLY public.audit_logs ALTER COLUMN id SET DEFAULT nextval('public.audit_logs_id_seq'::regclass);

ALTER TABLE ONLY public.favorite ALTER COLUMN id SET DEFAULT nextval('public.favorite_id_seq'::regclass);

ALTER TABLE ONLY public.feedback ALTER COLUMN id SET DEFAULT nextval('public.feedback_id_seq'::regclass);

ALTER TABLE ONLY public.notification ALTER COLUMN id SET DEFAULT nextval('public.notification_id_seq'::regclass);

ALTER TABLE ONLY public.notification_setting ALTER COLUMN id SET DEFAULT nextval('public.notification_setting_id_seq'::regclass);

ALTER TABLE ONLY public.region ALTER COLUMN id SET DEFAULT nextval('public.region_id_seq'::regclass);

ALTER TABLE ONLY public.session ALTER COLUMN id SET DEFAULT nextval('public.session_id_seq'::regclass);

ALTER TABLE ONLY public."user" ALTER COLUMN id SET DEFAULT nextval('public.user_id_seq'::regclass);

ALTER TABLE ONLY public.file
    ADD CONSTRAINT "PK_36b46d232307066b3a2c9ea3a1d" PRIMARY KEY (id);

ALTER TABLE ONLY public.admin_alert
    ADD CONSTRAINT "PK_44cf0b17edf69fb3f9b62c1ee7b" PRIMARY KEY (id);

ALTER TABLE ONLY public.account_cleanup
    ADD CONSTRAINT "PK_account_cleanup" PRIMARY KEY ("userId");

ALTER TABLE ONLY public.address
    ADD CONSTRAINT "PK_address_id" PRIMARY KEY (id);

ALTER TABLE ONLY public.audit_logs
    ADD CONSTRAINT "PK_audit_logs" PRIMARY KEY (id);

ALTER TABLE ONLY public.role
    ADD CONSTRAINT "PK_b36bcfe02fc8de3c57a8b2391c2" PRIMARY KEY (id);

ALTER TABLE ONLY public."user"
    ADD CONSTRAINT "PK_cace4a159ff9f2512dd42373760" PRIMARY KEY (id);

ALTER TABLE ONLY public.config
    ADD CONSTRAINT "PK_config_key" PRIMARY KEY (key);

ALTER TABLE ONLY public.status
    ADD CONSTRAINT "PK_e12743a7086ec826733f54e1d95" PRIMARY KEY (id);

ALTER TABLE ONLY public.session
    ADD CONSTRAINT "PK_f55da76ac1c3ac420f444d2ff11" PRIMARY KEY (id);

ALTER TABLE ONLY public.favorite
    ADD CONSTRAINT "PK_favorite_id" PRIMARY KEY (id);

ALTER TABLE ONLY public.feedback
    ADD CONSTRAINT "PK_feedback_id" PRIMARY KEY (id);

ALTER TABLE ONLY public.notification
    ADD CONSTRAINT "PK_notification_id" PRIMARY KEY (id);

ALTER TABLE ONLY public.notification_setting
    ADD CONSTRAINT "PK_notification_setting_id" PRIMARY KEY (id);

ALTER TABLE ONLY public.permission
    ADD CONSTRAINT "PK_permission_id" PRIMARY KEY (id);

ALTER TABLE ONLY public.region
    ADD CONSTRAINT "PK_region_id" PRIMARY KEY (id);

ALTER TABLE ONLY public.role_permission
    ADD CONSTRAINT "PK_role_permission" PRIMARY KEY (role_id, permission_id);

ALTER TABLE ONLY public.user_consent
    ADD CONSTRAINT "PK_user_consent" PRIMARY KEY (id);

ALTER TABLE ONLY public."user"
    ADD CONSTRAINT "REL_75e2be4ce11d447ef43be0e374" UNIQUE ("photoId");

ALTER TABLE ONLY public."user"
    ADD CONSTRAINT "UQ_e12875dfb3b1d92d7d7c5377e22" UNIQUE (email);

ALTER TABLE ONLY public.favorite
    ADD CONSTRAINT "UQ_favorite_user_target" UNIQUE ("userId", "targetType", "targetId");

ALTER TABLE ONLY public.notification_setting
    ADD CONSTRAINT "UQ_notification_setting_userId_category" UNIQUE ("userId", category);

ALTER TABLE ONLY public.region
    ADD CONSTRAINT "UQ_region_code" UNIQUE (code);

ALTER TABLE ONLY public."user"
    ADD CONSTRAINT "user_alipayOpenId_key" UNIQUE ("alipayOpenId");

ALTER TABLE ONLY public."user"
    ADD CONSTRAINT user_phone_key UNIQUE (phone);

ALTER TABLE ONLY public."user"
    ADD CONSTRAINT "user_wechatOpenId_key" UNIQUE ("wechatOpenId");

CREATE INDEX "IDX_3d2f174ef04fb312fdebd0ddc5" ON public.session USING btree ("userId");

CREATE INDEX "IDX_58e4dbff0e1a32a9bdc861bb29" ON public."user" USING btree ("firstName");

CREATE INDEX "IDX_PERMISSION_RESOURCE" ON public.permission USING btree (resource);

CREATE INDEX "IDX_ROLE_PERMISSION_PERMISSION" ON public.role_permission USING btree (permission_id);

CREATE INDEX "IDX_ROLE_PERMISSION_ROLE" ON public.role_permission USING btree (role_id);

CREATE INDEX "IDX_address_cityCode" ON public.address USING btree ("cityCode");

CREATE INDEX "IDX_address_deletedAt" ON public.address USING btree ("deletedAt") WHERE ("deletedAt" IS NULL);

CREATE INDEX "IDX_address_districtCode" ON public.address USING btree ("districtCode");

CREATE INDEX "IDX_address_provinceCode" ON public.address USING btree ("provinceCode");

CREATE INDEX "IDX_address_userId" ON public.address USING btree ("userId");

CREATE INDEX "IDX_address_userId_isDefault_createdAt" ON public.address USING btree ("userId", "isDefault" DESC, "createdAt" DESC);

CREATE INDEX "IDX_admin_alert_createdAt" ON public.admin_alert USING btree ("createdAt");

CREATE INDEX "IDX_admin_alert_type" ON public.admin_alert USING btree (type);

CREATE INDEX "IDX_audit_logs_action" ON public.audit_logs USING btree (action);

CREATE INDEX "IDX_audit_logs_createdAt" ON public.audit_logs USING btree ("createdAt");

CREATE INDEX "IDX_audit_logs_entityId" ON public.audit_logs USING btree ("entityId");

CREATE INDEX "IDX_audit_logs_entityType" ON public.audit_logs USING btree ("entityType");

CREATE INDEX "IDX_audit_logs_requestId" ON public.audit_logs USING btree ("requestId");

CREATE INDEX "IDX_audit_logs_userId" ON public.audit_logs USING btree ("userId");

CREATE INDEX "IDX_f0e1b4ecdca13b177e2e3a0613" ON public."user" USING btree ("lastName");

CREATE INDEX "IDX_favorite_targetId" ON public.favorite USING btree ("targetId");

CREATE INDEX "IDX_favorite_targetType" ON public.favorite USING btree ("targetType");

CREATE INDEX "IDX_favorite_userId" ON public.favorite USING btree ("userId");

CREATE INDEX "IDX_favorite_userId_subjectId" ON public.favorite USING btree ("userId", "subjectId");

CREATE INDEX "IDX_favorite_userId_targetType" ON public.favorite USING btree ("userId", "targetType");

CREATE INDEX "IDX_feedback_errataId" ON public.feedback USING btree ("errataId");

CREATE INDEX "IDX_feedback_questionId" ON public.feedback USING btree ("questionId");

CREATE INDEX "IDX_feedback_status" ON public.feedback USING btree (status);

CREATE INDEX "IDX_feedback_type" ON public.feedback USING btree (type);

CREATE INDEX "IDX_feedback_userId" ON public.feedback USING btree ("userId");

CREATE INDEX "IDX_file_ownerUserId" ON public.file USING btree ("ownerUserId");

CREATE INDEX "IDX_file_path" ON public.file USING btree (path);

CREATE INDEX "IDX_file_sha256" ON public.file USING btree (sha256);

CREATE INDEX "IDX_notification_category" ON public.notification USING btree (category);

CREATE INDEX "IDX_notification_createdAt" ON public.notification USING btree ("createdAt");

CREATE INDEX "IDX_notification_isRead" ON public.notification USING btree ("isRead");

CREATE INDEX "IDX_notification_setting_userId" ON public.notification_setting USING btree ("userId");

CREATE INDEX "IDX_notification_userId" ON public.notification USING btree ("userId");

CREATE INDEX "IDX_region_code" ON public.region USING btree (code);

CREATE INDEX "IDX_region_level" ON public.region USING btree (level);

CREATE INDEX "IDX_region_level_parentCode" ON public.region USING btree (level, "parentCode");

CREATE INDEX "IDX_region_parentCode" ON public.region USING btree ("parentCode");

CREATE INDEX "IDX_session_deletedAt" ON public.session USING btree ("deletedAt") WHERE ("deletedAt" IS NULL);

CREATE INDEX "IDX_user_deletedAt" ON public."user" USING btree ("deletedAt") WHERE ("deletedAt" IS NULL);

CREATE INDEX "IDX_user_phone" ON public."user" USING btree (phone);

CREATE INDEX "IDX_user_wechatOpenId" ON public."user" USING btree ("wechatOpenId");

CREATE INDEX "IDX_user_wechatUnionId" ON public."user" USING btree ("wechatUnionId");

CREATE UNIQUE INDEX "UQ_address_userId_default" ON public.address USING btree ("userId") WHERE ("isDefault" AND ("deletedAt" IS NULL));

CREATE UNIQUE INDEX "UQ_admin_alert_dedupKey_open" ON public.admin_alert USING btree ("dedupKey") WHERE ("acknowledgedAt" IS NULL);

CREATE UNIQUE INDEX "UQ_user_consent_userId_type_version" ON public.user_consent USING btree ("userId", type, version);

ALTER TABLE ONLY public.session
    ADD CONSTRAINT "FK_3d2f174ef04fb312fdebd0ddc53" FOREIGN KEY ("userId") REFERENCES public."user"(id);

ALTER TABLE ONLY public."user"
    ADD CONSTRAINT "FK_75e2be4ce11d447ef43be0e374f" FOREIGN KEY ("photoId") REFERENCES public.file(id);

ALTER TABLE ONLY public.role_permission
    ADD CONSTRAINT "FK_ROLE_PERMISSION_PERMISSION" FOREIGN KEY (permission_id) REFERENCES public.permission(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.role_permission
    ADD CONSTRAINT "FK_ROLE_PERMISSION_ROLE" FOREIGN KEY (role_id) REFERENCES public.role(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.account_cleanup
    ADD CONSTRAINT "FK_account_cleanup_user" FOREIGN KEY ("userId") REFERENCES public."user"(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.address
    ADD CONSTRAINT "FK_address_user" FOREIGN KEY ("userId") REFERENCES public."user"(id) ON DELETE CASCADE;

ALTER TABLE ONLY public."user"
    ADD CONSTRAINT "FK_c28e52f758e7bbc53828db92194" FOREIGN KEY ("roleId") REFERENCES public.role(id);

ALTER TABLE ONLY public."user"
    ADD CONSTRAINT "FK_dc18daa696860586ba4667a9d31" FOREIGN KEY ("statusId") REFERENCES public.status(id);

ALTER TABLE ONLY public.favorite
    ADD CONSTRAINT "FK_favorite_user" FOREIGN KEY ("userId") REFERENCES public."user"(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.feedback
    ADD CONSTRAINT "FK_feedback_user" FOREIGN KEY ("userId") REFERENCES public."user"(id) ON DELETE SET NULL;

ALTER TABLE ONLY public.notification_setting
    ADD CONSTRAINT "FK_notification_setting_user" FOREIGN KEY ("userId") REFERENCES public."user"(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.notification
    ADD CONSTRAINT "FK_notification_user" FOREIGN KEY ("userId") REFERENCES public."user"(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.user_consent
    ADD CONSTRAINT "FK_user_consent_user" FOREIGN KEY ("userId") REFERENCES public."user"(id) ON DELETE CASCADE;
