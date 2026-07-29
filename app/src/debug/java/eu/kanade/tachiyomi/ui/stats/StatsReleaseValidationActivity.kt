package eu.kanade.tachiyomi.ui.stats

import android.os.Bundle
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.view.setComposeContent

class StatsReleaseValidationActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setComposeContent {
            Navigator(StatsScreen())
        }
    }
}
