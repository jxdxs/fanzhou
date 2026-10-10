package com.kevin.fanzhou

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kevin.fanzhou.ui.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FanzhouTheme { AppRoot() } }
    }
}

object Routes {
    fun library(sid: String, id: String, name: String) = "lib/$sid/$id/${Uri.encode(name)}"
    fun detail(sid: String, id: String) = "item/$sid/$id"
    fun dav(sid: String, url: String? = null) = "dav/$sid?url=${Uri.encode(url ?: "")}"
    fun search(sid: String) = "search/$sid"
    fun editSource(type: String, id: String? = null) =
        if (type in com.kevin.fanzhou.data.Source.CLOUD_TYPES) cloudLogin(type, id) else "edit/$type?id=${id ?: ""}"
    fun cloudLogin(type: String, id: String? = null) = "cloudlogin/$type?id=${id ?: ""}"
    /** [path] = names of the ancestor folders between the drive root and [dir] (used for the breadcrumb row). */
    fun cloud(sid: String, dir: String? = null, title: String? = null, path: List<String> = emptyList()) =
        "cloud/$sid?dir=${Uri.encode(dir ?: "")}&title=${Uri.encode(title ?: "")}&path=${Uri.encode(path.joinToString("\u001F"))}"
    fun open(s: com.kevin.fanzhou.data.Source) = when {
        s.isJellyfin -> "home"
        s.isCloud -> cloud(s.id)
        else -> dav(s.id) // WebDAV and SMB share the folder screen
    }
}

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val tabs = listOf("home" to "首页", "sources" to "媒体源", "settings" to "设置")
    Scaffold(
        bottomBar = {
            if (route in tabs.map { it.first }) {
                NavigationBar(containerColor = Surface1) {
                    tabs.forEach { (r, label) ->
                        NavigationBarItem(
                            selected = route == r,
                            onClick = { nav.navigate(r) { popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true } },
                            icon = {
                                Icon(
                                    when (r) { "home" -> Icons.Filled.Home; "sources" -> Icons.Filled.List; else -> Icons.Filled.Settings },
                                    contentDescription = label,
                                )
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavGraph(nav, Modifier.padding(bottom = pad.calculateBottomPadding()))
    }
}

@Composable
fun NavGraph(nav: NavHostController, modifier: Modifier) {
    NavHost(nav, startDestination = "home", modifier = modifier) {
        composable("home") { HomeScreen(nav) }
        composable("sources") { SourcesScreen(nav) }
        composable("settings") { SettingsScreen() }
        composable("edit/{type}?id={id}") { e ->
            EditSourceScreen(nav, e.arguments?.getString("type") ?: "jellyfin", e.arguments?.getString("id")?.ifEmpty { null })
        }
        composable("lib/{sid}/{id}/{name}") { e ->
            LibraryScreen(nav, e.arguments!!.getString("sid")!!, e.arguments!!.getString("id")!!, Uri.decode(e.arguments!!.getString("name")!!))
        }
        composable("item/{sid}/{id}") { e ->
            DetailScreen(nav, e.arguments!!.getString("sid")!!, e.arguments!!.getString("id")!!)
        }
        composable("dav/{sid}?url={url}") { e ->
            WebDavScreen(nav, e.arguments!!.getString("sid")!!, e.arguments?.getString("url")?.let { Uri.decode(it) }?.ifEmpty { null })
        }
        composable("cloudlogin/{type}?id={id}") { e ->
            CloudLoginScreen(nav, e.arguments?.getString("type") ?: "p115", e.arguments?.getString("id")?.ifEmpty { null })
        }
        composable("cloud/{sid}?dir={dir}&title={title}&path={path}") { e ->
            CloudScreen(
                nav, e.arguments!!.getString("sid")!!,
                e.arguments?.getString("dir")?.let { Uri.decode(it) }?.ifEmpty { null },
                e.arguments?.getString("title")?.let { Uri.decode(it) }?.ifEmpty { null },
                e.arguments?.getString("path")?.let { Uri.decode(it) }?.takeIf { it.isNotEmpty() }?.split('\u001F') ?: emptyList(),
            )
        }
        composable("search/{sid}") { e -> SearchScreen(nav, e.arguments!!.getString("sid")!!) }
    }
}
