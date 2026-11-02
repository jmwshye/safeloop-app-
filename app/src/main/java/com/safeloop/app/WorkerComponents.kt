package com.safeloop.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Forest=Color(0xFF176B49)
val Ink=Color(0xFF20352A)
val Muted=Color(0xFF7F8D83)
val CanvasColor=Color(0xFFF5F7F3)
val PaleGreen=Color(0xFFECF4EB)
val Line=Color(0xFFE4EAE2)
val Amber=Color(0xFF9B7425)
val PaleAmber=Color(0xFFFFF5DE)
val Danger=Color(0xFFBB4038)

@Composable fun WorkerTheme(content: @Composable () -> Unit){MaterialTheme(colorScheme=lightColorScheme(primary=Forest,onPrimary=Color.White,secondary=Forest,background=CanvasColor,surface=Color.White,onSurface=Ink,onBackground=Ink,outline=Line,error=Danger),content=content)}

private val iconPaths=mapOf(
 "home" to "M3,10 L12,3 L21,10 M5,9 L5,21 L10,21 L10,14 L14,14 L14,21 L19,21 L19,9",
 "shield" to "M12,3 L20,6 L20,12 C20,17 16,20 12,22 C8,20 4,17 4,12 L4,6 Z M8,12 L11,15 L16,10",
 "people" to "M16,21 L16,19 C16,16 13,15 9,15 C5,15 2,16 2,19 L2,21 M21,21 L21,19 C21,17 20,16 18,16 M9,3 A4,4 0,1 1,9,11 A4,4 0,1 1,9,3 M17,3 C22,4 22,10 17,11",
 "report" to "M10,4 C11,2 13,2 14,4 L22,18 C23,20 22,22 20,22 L4,22 C2,22 1,20 2,18 Z M12,9 L12,14 M12,18 L12,18.1",
 "records" to "M7,3 L17,3 L17,6 L7,6 Z M7,4 L4,4 L4,22 L20,22 L20,4 L17,4 M8,11 L16,11 M8,15 L16,15 M8,19 L13,19",
 "person" to "M12,3 A4,4 0,1 1,12,11 A4,4 0,1 1,12,3 M4,22 L4,20 C4,16 7,15 12,15 C17,15 20,16 20,20 L20,22",
 "bell" to "M5,16 L6,14 L6,9 C6,1 18,1 18,9 L18,14 L20,17 L4,17 Z M10,21 L14,21",
 "chevron" to "M9,5 L16,12 L9,19",
 "back" to "M15,5 L8,12 L15,19",
 "arrow" to "M4,12 L20,12 M14,6 L20,12 L14,18",
 "check" to "M4,12 L10,18 L21,6",
 "close" to "M6,6 L18,18 M18,6 L6,18",
 "clock" to "M12,2 A10,10 0,1 1,12,22 A10,10 0,1 1,12,2 M12,6 L12,12 L16,14",
 "calendar" to "M5,5 L19,5 Q21,5 21,7 L21,20 Q21,22 19,22 L5,22 Q3,22 3,20 L3,7 Q3,5 5,5 M3,11 L21,11 M8,2 L8,7 M16,2 L16,7",
 "camera" to "M8,6 L10,3 L14,3 L16,6 L21,6 L21,21 L3,21 L3,6 Z M12,9 A4,4 0,1 1,12,17 A4,4 0,1 1,12,9",
 "pin" to "M12,22 C10,19 4,14 4,9 A8,8 0,1 1,20,9 C20,14 14,19 12,22 M12,6 A3,3 0,1 1,12,12 A3,3 0,1 1,12,6",
 "phone" to "M6,3 L10,8 L7,11 C8,14 10,16 13,17 L16,14 L21,18 L21,21 C12,24 0,12 3,3 Z",
 "activity" to "M2,12 L6,12 L10,3 L14,21 L18,12 L22,12",
 "helmet" to "M4,17 L4,12 C4,7 7,4 12,4 C17,4 20,7 20,12 L20,17 M9,4 L9,11 M15,4 L15,11 M2,17 L22,17 L22,21 L2,21 Z",
 "info" to "M12,2 A10,10 0,1 1,12,22 A10,10 0,1 1,12,2 M12,11 L12,17 M12,7 L12,7.1",
 "download" to "M12,2 L12,16 M7,11 L12,16 L17,11 M3,16 L3,22 L21,22 L21,16",
 "settings" to "M4,7 L20,7 M4,17 L20,17 M8,3 L8,11 M16,13 L16,21",
 "logout" to "M9,3 L3,3 L3,21 L9,21 M11,12 L22,12 M17,7 L22,12 L17,17",
 "plus" to "M12,4 L12,20 M4,12 L20,12",
 "link" to "M10,7 L13,4 C18,-1 25,6 20,11 L17,14 M14,17 L11,20 C6,25 -1,18 4,13 L7,10 M8,16 L16,8",
 "lock" to "M7,10 L7,7 C7,0 17,0 17,7 L17,10 M4,10 L20,10 L20,22 L4,22 Z M12,15 L12,18",
 "spark" to "M12,2 L15,9 L22,12 L15,15 L12,22 L9,15 L2,12 L9,9 Z"
)
@Composable fun SLIcon(name:String,modifier:Modifier=Modifier,tint:Color=Forest,description:String?=null){
 val path=remember(name){PathParser().parsePathString(iconPaths[name]?:iconPaths.getValue("info")).toPath()}
 Canvas(modifier.size(24.dp).then(if(description==null)Modifier else Modifier.semantics{contentDescription=description})){
  scale(size.width/24f,size.height/24f,pivot=Offset.Zero){drawPath(path,tint,style=Stroke(width=1.65f,cap=StrokeCap.Round,join=StrokeJoin.Round))}
 }
}
@Composable fun SLPill(text:String,amber:Boolean=false,neutral:Boolean=false){val tint=if(amber)Amber else if(neutral)Muted else Forest;val bg=if(amber)PaleAmber else if(neutral)CanvasColor else PaleGreen
 Row(Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal=9.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){
  Box(Modifier.size(4.dp).background(tint,CircleShape));Text(text,fontSize=11.sp,color=tint,fontWeight=FontWeight.SemiBold)
 }
}
@Composable fun SLCard(modifier:Modifier=Modifier,color:Color=Color.White,content:@Composable ColumnScope.()->Unit){Surface(modifier.fillMaxWidth(),color=color,shape=RoundedCornerShape(20.dp),border=BorderStroke(1.dp,if(color==Color.White)Line else color)) {Column(Modifier.padding(19.dp),verticalArrangement=Arrangement.spacedBy(14.dp),content=content)}}
@Composable fun SLButton(text:String,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,secondary:Boolean=false,icon:String?=null){
 if(secondary)OutlinedButton(onClick,modifier.fillMaxWidth().heightIn(min=56.dp),enabled=enabled,shape=RoundedCornerShape(13.dp),border=BorderStroke(1.dp,Line),colors=ButtonDefaults.outlinedButtonColors(contentColor=Forest)){
  if(icon!=null){SLIcon(icon,Modifier.size(19.dp));Spacer(Modifier.width(8.dp))};Text(text,fontSize=15.sp,fontWeight=FontWeight.Bold)
 }else Button(onClick,modifier.fillMaxWidth().heightIn(min=60.dp),enabled=enabled,shape=RoundedCornerShape(13.dp),colors=ButtonDefaults.buttonColors(containerColor=Forest,disabledContainerColor=Line)){
  if(icon!=null){SLIcon(icon,Modifier.size(19.dp),Color.White);Spacer(Modifier.width(8.dp))};Text(text,fontSize=16.sp,fontWeight=FontWeight.Bold)
 }
}
@Composable fun SLField(label:String,value:String,onValue:(String)->Unit,placeholder:String="",singleLine:Boolean=true,modifier:Modifier=Modifier){Column(verticalArrangement=Arrangement.spacedBy(8.dp),modifier=modifier){Text(label,fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=Ink);OutlinedTextField(value,onValue,modifier=Modifier.fillMaxWidth(),placeholder={Text(placeholder,fontSize=14.sp,color=Muted)},singleLine=singleLine,minLines=if(singleLine)1 else 3,shape=RoundedCornerShape(13.dp),colors=OutlinedTextFieldDefaults.colors(unfocusedBorderColor=Line,focusedBorderColor=Forest,unfocusedContainerColor=Color.White,focusedContainerColor=Color.White),textStyle=MaterialTheme.typography.bodyLarge.copy(fontSize=15.sp,color=Ink))}}
@Composable fun PageHeading(eyebrow:String,title:String,subtitle:String?=null){Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text(eyebrow,fontSize=11.sp,color=Muted,fontWeight=FontWeight.Medium,letterSpacing=.7.sp);Text(title,fontSize=27.sp,lineHeight=36.sp,color=Ink,fontWeight=FontWeight.ExtraBold,letterSpacing=(-.8).sp);if(subtitle!=null)Text(subtitle,fontSize=13.sp,lineHeight=20.sp,color=Muted)}}
@Composable fun SectionHeading(title:String,action:String?=null,onAction:()->Unit={}){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){Text(title,fontSize=17.sp,fontWeight=FontWeight.Bold,color=Ink,letterSpacing=(-.4).sp);if(action!=null)TextButton(onAction,contentPadding=PaddingValues(horizontal=0.dp)){Text(action,fontSize=12.sp,color=Muted);Spacer(Modifier.width(3.dp));SLIcon("chevron",Modifier.size(14.dp),Muted)}}}
@Composable fun Avatar(name:String,size:Int=44){Box(Modifier.size(size.dp).background(PaleGreen,CircleShape),contentAlignment=Alignment.Center){Text(name.take(1),fontSize=(size*.34).sp,fontWeight=FontWeight.Bold,color=Forest)}}
@Composable fun SmallNote(text:String,amber:Boolean=false){Row(Modifier.fillMaxWidth().background(if(amber)PaleAmber else PaleGreen,RoundedCornerShape(12.dp)).padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
 SLIcon(if(amber)"report" else "info",Modifier.size(16.dp),if(amber)Amber else Forest)
 Text(text,fontSize=12.sp,lineHeight=18.sp,color=if(amber)Amber else Forest)
}}
@Composable fun DetailRow(label:String,value:String){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text(label,fontSize=13.sp,color=Muted);Text(value,fontSize=14.sp,fontWeight=FontWeight.SemiBold,color=Ink,maxLines=2)}}
@Composable fun MenuRow(icon:String,title:String,subtitle:String?=null,onClick:()->Unit){Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=11.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
 Box(Modifier.size(40.dp).background(CanvasColor,RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center){SLIcon(icon,Modifier.size(20.dp))}
 Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(title,fontSize=14.sp,fontWeight=FontWeight.SemiBold,color=Ink);if(subtitle!=null)Text(subtitle,fontSize=11.sp,color=Muted,lineHeight=17.sp)}
 SLIcon("chevron",Modifier.size(17.dp),Muted)
}}
@Composable fun EmptyState(icon:String,title:String,detail:String){Column(Modifier.fillMaxWidth().padding(vertical=22.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){Box(Modifier.size(48.dp).background(CanvasColor,CircleShape),contentAlignment=Alignment.Center){SLIcon(icon,tint=Muted)};Text(title,fontSize=14.sp,fontWeight=FontWeight.SemiBold,color=Ink);Text(detail,fontSize=12.sp,lineHeight=18.sp,color=Muted)}}
@Composable fun PhotoPreview(path:String,modifier:Modifier=Modifier){val bitmap=remember(path){BitmapFactory.decodeFile(path,BitmapFactory.Options().apply{inSampleSize=3})};if(bitmap!=null)Image(bitmap.asImageBitmap(),"촬영한 현장 주변 사진",modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(14.dp)),contentScale=ContentScale.Crop)}
@Composable fun PhotoCapture(path:String?,onCapture:()->Unit){if(path!=null){PhotoPreview(path);TextButton(onCapture,Modifier.fillMaxWidth()){SLIcon("camera",Modifier.size(16.dp));Spacer(Modifier.width(6.dp));Text("사진 다시 촬영",fontSize=13.sp)}}else Surface(Modifier.fillMaxWidth().height(160.dp).clickable(onClick=onCapture),color=CanvasColor,shape=RoundedCornerShape(16.dp),border=BorderStroke(1.dp,Line)){
 Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Box(Modifier.size(44.dp).background(PaleGreen,CircleShape),contentAlignment=Alignment.Center){SLIcon("camera")};Spacer(Modifier.height(10.dp));Text("현장 사진 촬영",fontSize=14.sp,fontWeight=FontWeight.Bold,color=Forest);Spacer(Modifier.height(5.dp));Text("위험 위치와 주변이 잘 보이게 찍어 주세요.",fontSize=11.sp,color=Muted)}
}}
@Composable fun EmergencyStrip(onDial:()->Unit){Row(Modifier.fillMaxWidth().background(Color(0xFFFFF9F6)).clickable(onClick=onDial).padding(horizontal=22.dp,vertical=13.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)){
 SLIcon("phone",Modifier.size(17.dp),Danger);Text("긴급 상황에는",fontSize=12.sp,color=Danger);Text("119 전화",fontSize=13.sp,fontWeight=FontWeight.Bold,color=Danger);Spacer(Modifier.weight(1f));SLIcon("arrow",Modifier.size(17.dp),Danger)
}}
