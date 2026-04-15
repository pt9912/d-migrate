rootProject.name = "d-migrate"

// Hexagon (innen)
include("hexagon:core")
include("hexagon:ports")
include("hexagon:application")
include("hexagon:profiling")

// Adapters (außen)
include("adapters:driven:driver-common")
include("adapters:driven:driver-postgresql")
include("adapters:driven:driver-mysql")
include("adapters:driven:driver-sqlite")
include("adapters:driven:formats")
include("adapters:driven:integrations")
include("adapters:driven:streaming")
include("adapters:driving:cli")
