plugins {
    `java-library`
}

dependencies {
    api(project(":hexagon:ports"))
}

kover {
    reports {
        verify {
            rule {
                minBound(90)
            }
        }
    }
}
