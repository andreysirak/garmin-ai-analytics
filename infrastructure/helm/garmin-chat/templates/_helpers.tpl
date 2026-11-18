{{- define "garmin-chat.name" -}}
{{- .Release.Name | trunc 40 | trimSuffix "-" -}}
{{- end -}}

{{- define "garmin-chat.labels" -}}
app.kubernetes.io/name: garmin-chat
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "garmin-chat.executionTimeoutSeconds" -}}
{{- default (add (mul 2 .Values.chat.modelTimeoutSeconds) 60) .Values.chat.executionTimeoutSeconds -}}
{{- end -}}
