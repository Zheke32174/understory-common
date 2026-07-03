# No reflection / native callers in :common-backup. The codec interface
# is invoked from Kotlin only, all classes are kept by ordinary call-graph
# tracing.
