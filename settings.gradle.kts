rootProject.name = "d-migrate"

// Hexagon (innen)
include("hexagon:core")
include("hexagon:ports")

// Bestehende Module (werden in späteren Phasen unter hexagon/ bzw. adapters/ eingeordnet)
include("d-migrate-driver-api")
include("d-migrate-driver-postgresql")
include("d-migrate-driver-mysql")
include("d-migrate-driver-sqlite")
include("d-migrate-formats")
include("d-migrate-streaming")
include("d-migrate-cli")
