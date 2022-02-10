package com.github.andy2003.intellijswaggerannotationtoopenapiplugin.services

import com.intellij.openapi.project.Project
import com.github.andy2003.intellijswaggerannotationtoopenapiplugin.MyBundle

class MyProjectService(project: Project) {

    init {
        println(MyBundle.message("projectService", project.name))
    }
}
