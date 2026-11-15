package tech.xingyunqi.template.infrastructure.files.web;

import tech.xingyunqi.template.contract.model.FileType;

/** s3-presigned 驱动的上传响应。 */
record PresignedFileResponse(FileType file, String uploadSignedUrl) {}
