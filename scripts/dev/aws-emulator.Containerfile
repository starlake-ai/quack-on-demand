# The AWS Emulator as AWS documents it (no prebuilt image is published), with the architecture as a
# build argument. Built and run by scripts/dev/audit-s3-emulator.sh for S3AuditSinkSpec.
FROM public.ecr.aws/amazonlinux/amazonlinux:2023-minimal
ARG RPM_ARCH=aarch64
ADD https://downloads.aws.com/aws-emulator/packages/aws-emulator/LTS/latest/aws-emulator-${RPM_ARCH}.rpm /tmp/aws-emulator.rpm
RUN rpm -i /tmp/aws-emulator.rpm && rm /tmp/aws-emulator.rpm \
    && mkdir -p /home/emulator && chown 1000 /home/emulator
USER 1000
ENV HOME=/home/emulator
EXPOSE 9297
ENTRYPOINT ["aws-emulator", "-b", "0.0.0.0"]
