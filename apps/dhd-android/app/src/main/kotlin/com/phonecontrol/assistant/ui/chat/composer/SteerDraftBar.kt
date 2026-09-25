package com.phonecontrol.assistant.ui.chat.composer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonecontrol.assistant.R
import com.phonecontrol.assistant.ui.theme.LocalAssistantColors

@Composable
internal fun SteerDraftBar(
    text: String,
    onSteer: () -> Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
) {
    val colors = LocalAssistantColors.current
    Surface(
        color = colors.surfaceCard,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, colors.borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "↪",
                color = colors.textSecondary,
                fontSize = 18.sp,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(
                text = text,
                color = colors.textPrimary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "↪ Steer",
                color = colors.textSecondary,
                fontSize = 12.sp,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSteer() }
                    .padding(horizontal = 8.dp, vertical = 7.dp),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = "Discard steer draft",
                tint = colors.textSecondary,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable { onDismiss() }
                    .padding(8.dp),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                painter = painterResource(R.drawable.ic_compose_new),
                contentDescription = "Edit steer draft",
                tint = colors.textSecondary,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable { onEdit() }
                    .padding(7.dp),
            )
        }
    }
}
