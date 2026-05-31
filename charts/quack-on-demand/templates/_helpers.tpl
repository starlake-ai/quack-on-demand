{{/*
Standard naming helpers, copied from the chart-create scaffolding template.
*/}}

{{- define "qod.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "qod.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "qod.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "qod.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "qod.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/*
Common labels — applied to every K8s object the chart manages, plus to the
spawned Quack node pods via the manager's `podLabel` config (matches the
label selector used by KubernetesQuackBackend.discoverExisting()).
*/}}
{{- define "qod.labels" -}}
helm.sh/chart: {{ include "qod.chart" . }}
{{ include "qod.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "qod.selectorLabels" -}}
app.kubernetes.io/name: {{ include "qod.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/*
Image reference. Tag defaults to .Chart.AppVersion when values.image.tag is
empty so a `helm upgrade` to a new chart version pulls the matching image.
*/}}
{{- define "qod.image" -}}
{{- $tag := default .Chart.AppVersion .Values.image.tag -}}
{{ .Values.image.repository }}:{{ $tag }}
{{- end -}}

{{/*
Resolve the Postgres password secret reference. Returns either the existing
secret name+key the user provided, or the chart-managed one.
*/}}
{{- define "qod.postgresPasswordSecretName" -}}
{{- if .Values.postgres.existingSecret -}}
{{- .Values.postgres.existingSecret -}}
{{- else -}}
{{- include "qod.fullname" . }}-postgres
{{- end -}}
{{- end -}}

{{- define "qod.postgresPasswordSecretKey" -}}
{{- if .Values.postgres.existingSecret -}}
{{- .Values.postgres.existingSecretKey -}}
{{- else -}}
{{- "pgPassword" -}}
{{- end -}}
{{- end -}}

{{- define "qod.adminPasswordSecretName" -}}
{{- if .Values.admin.existingSecret -}}
{{- .Values.admin.existingSecret -}}
{{- else -}}
{{- include "qod.fullname" . }}-admin
{{- end -}}
{{- end -}}

{{- define "qod.adminPasswordSecretKey" -}}
{{- if .Values.admin.existingSecret -}}
{{- .Values.admin.existingSecretKey -}}
{{- else -}}
{{- "adminPassword" -}}
{{- end -}}
{{- end -}}

{{- define "qod.apiKeySecretName" -}}
{{- if .Values.apiKey.existingSecret -}}
{{- .Values.apiKey.existingSecret -}}
{{- else -}}
{{- include "qod.fullname" . }}-apikey
{{- end -}}
{{- end -}}