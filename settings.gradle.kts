rootProject.name = "Trenify"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":androidApp")
include(":shared:app")
include(":shared:core:model")
include(":shared:core:domain")
include(":shared:core:provider-api")
include(":shared:core:network")
include(":shared:core:database")
include(":shared:core:platform")
include(":shared:core:ui")
include(":shared:core:testing")
include(":shared:data")
include(":shared:provider:viaggiatreno")
include(":shared:provider:journey")
include(":shared:provider:pricing")
include(":shared:provider:mit-strikes")
include(":shared:feature:home")
include(":shared:feature:journey")
include(":shared:feature:station")
include(":shared:feature:train")
include(":shared:feature:monitoring")
include(":shared:feature:strikes")
include(":shared:feature:favorites")
include(":shared:feature:settings")
