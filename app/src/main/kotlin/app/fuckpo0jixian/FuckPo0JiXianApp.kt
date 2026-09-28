package app.fuckpo0jixian

import android.app.Application

class FuckPo0JiXianApp : Application() {
    lateinit var controller: Controller
    override fun onCreate() {
        super.onCreate()
        LifeLog.init(this); LifeLog.processStart()
        controller = Controller(this); controller.start()
    }
}
