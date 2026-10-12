package com.example.livenotification

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp


/**
 * 行程类型选择页
 * 进入后选择"高铁/火车"或"航班"
 */
@Composable
fun TripTypeSelectScreen(
    onBack: () -> Unit,
    onSelectTrain: () -> Unit,
    onSelectFlight: () -> Unit
) {

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {

        Button(onClick = onBack) {
            Text("返回")
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = "选择行程类型",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(Modifier.height(20.dp))

        // ============ 高铁/火车 ============
        TripTypeCard(
            icon = Icons.Filled.Train,
            title = "高铁 / 火车",
            subtitle = "创建列车实时通知",
            onClick = onSelectTrain
        )

        Spacer(Modifier.height(12.dp))

        // ============ 航班 ============
        TripTypeCard(
            icon = Icons.Filled.Flight,
            title = "航班",
            subtitle = "创建航班实时通知",
            onClick = onSelectFlight
        )
    }
}


@Composable
private fun TripTypeCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}