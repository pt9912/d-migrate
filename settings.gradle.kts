rootProject.name = "d-migrate"

// Hexagon (innen)
include("hexagon:core")
include("hexagon:ports")

// Adapters (außen)
include("adapters:driven:driver-common")
include("d-migrate-driver-postgresql")
include("d-migrate-driver-mysql")
include("d-migrate-driver-sqlite")
include("d-migrate-formats")
include("d-migrate-streaming")
include("d-migrate-cli")
