package io.legado.app.help

import io.legado.app.data.entities.BaseSource

/** 替换规则脚本里的 `java` 对象。必须放在脚本可见的包里，io.legado.web 下的类会被 RhinoClassShutter 屏蔽成 null */
class ReplaceRuleJs(private val tag: String) : JsExtensions {
    override fun getSource(): BaseSource? = null
    override fun getTag(): String = tag
}
