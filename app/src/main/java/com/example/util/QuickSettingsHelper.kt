package com.example.util

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import com.example.R
import com.example.service.TvPowerTileService
import com.example.service.TvRemoteTileService

object QuickSettingsHelper {

    fun requestAddRemoteTile(context: Context, onResult: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val sbm = context.getSystemService(StatusBarManager::class.java)
            val component = ComponentName(context, TvRemoteTileService::class.java)
            sbm?.requestAddTileService(
                component,
                "Control TV",
                Icon.createWithResource(context, R.drawable.ic_qs_remote_tile),
                context.mainExecutor
            ) { result ->
                val msg = when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "¡Botón 'Control TV' añadido a tus Ajustes Rápidos!"
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "El botón 'Control TV' ya está en tus Ajustes Rápidos."
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "Petición no completada."
                    else -> "Puedes agregarlo arrastrándolo desde el menú de edición de ajustes rápidos."
                }
                onResult(msg)
            } ?: onResult("Servicio de barra de estado no disponible.")
        } else {
            onResult("Desliza dos veces la barra superior de Android, toca el lápiz ✏️ de editar y arrastra 'Control TV' a tus accesos activos.")
        }
    }

    fun requestAddPowerTile(context: Context, onResult: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val sbm = context.getSystemService(StatusBarManager::class.java)
            val component = ComponentName(context, TvPowerTileService::class.java)
            sbm?.requestAddTileService(
                component,
                "Encender TV",
                Icon.createWithResource(context, R.drawable.ic_qs_power_tile),
                context.mainExecutor
            ) { result ->
                val msg = when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "¡Botón 'Encender TV' añadido a tus Ajustes Rápidos!"
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "El botón 'Encender TV' ya está en tus Ajustes Rápidos."
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "Petición no completada."
                    else -> "Puedes agregarlo arrastrándolo desde el menú de edición de ajustes rápidos."
                }
                onResult(msg)
            } ?: onResult("Servicio de barra de estado no disponible.")
        } else {
            onResult("Desliza dos veces la barra superior de Android, toca el lápiz ✏️ de editar y arrastra 'Encender TV' a tus accesos activos.")
        }
    }
}
