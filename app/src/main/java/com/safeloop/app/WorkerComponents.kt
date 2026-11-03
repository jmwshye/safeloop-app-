package com.safeloop.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 와이어프레임 7장 색 기준 (docs/wireframe.md 2장)
val Brand=Color(0xFF1D5FD1)
val BrandDark=Color(0xFF1546A0)
val BrandSoft=Color(0xFFE9F1FE)
val Ink=Color(0xFF1F2A37)
val Muted=Color(0xFF4B5B6B)
val CanvasColor=Color(0xFFF6F8FB)
val Line=Color(0xFFE1E7EE)
val NeutralFill=Color(0xFFEAEFF5)
val WarningYellow=Color(0xFFFFC20E)
val Ok=Color(0xFF0E7A4E)
val OkSoft=Color(0xFFE4F5EC)
val Amber=Color(0xFF8A5300)
val PaleAmber=Color(0xFFFFF4DC)
val Danger=Color(0xFFC8201A)
val DangerSoft=Color(0xFFFDECEA)

@Composable fun WorkerTheme(content: @Composable () -> Unit){MaterialTheme(colorScheme=lightColorScheme(primary=Brand,onPrimary=Color.White,secondary=Brand,background=CanvasColor,surface=Color.White,onSurface=Ink,onBackground=Ink,outline=Line,error=Danger,primaryContainer=BrandSoft,onPrimaryContainer=Brand),content=content)}

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
 "mic" to "M12,3 A3,3 0,0 1,15,6 L15,11 A3,3 0,0 1,9,11 L9,6 A3,3 0,0 1,12,3 M5,11 C5,20 19,20 19,11 M12,18 L12,22",
 "link" to "M10,7 L13,4 C18,-1 25,6 20,11 L17,14 M14,17 L11,20 C6,25 -1,18 4,13 L7,10 M8,16 L16,8",
 "lock" to "M7,10 L7,7 C7,0 17,0 17,7 L17,10 M4,10 L20,10 L20,22 L4,22 Z M12,15 L12,18",
 "spark" to "M12,2 L15,9 L22,12 L15,15 L12,22 L9,15 L2,12 L9,9 Z"
)
@Composable fun SLIcon(name:String,modifier:Modifier=Modifier,tint:Color=Brand,description:String?=null){
 val path=remember(name){PathParser().parsePathString(iconPaths[name]?:iconPaths.getValue("info")).toPath()}
 Canvas(modifier.size(24.dp).then(if(description==null)Modifier else Modifier.semantics{contentDescription=description})){
  scale(size.width/24f,size.height/24f,pivot=Offset.Zero){drawPath(path,tint,style=Stroke(width=1.65f,cap=StrokeCap.Round,join=StrokeJoin.Round))}
 }
}
// 상태 칩. fact는 사람이 확인(실선), estimate는 시스템이 추정(점선)
@Composable fun SLPill(text:String,amber:Boolean=false,neutral:Boolean=false,danger:Boolean=false,fact:Boolean=false,estimate:Boolean=false,brand:Boolean=false){val tint=if(danger)Danger else if(amber)Amber else if(neutral)Muted else if(brand)Brand else Ok;val bg=if(fact||estimate)Color.White else if(danger)DangerSoft else if(amber)PaleAmber else if(neutral)NeutralFill else if(brand)BrandSoft else OkSoft
 val edge=when{
  estimate->Modifier.drawBehind{val w=1.5.dp.toPx();drawRoundRect(tint,topLeft=Offset(w/2,w/2),size=Size(size.width-w,size.height-w),cornerRadius=CornerRadius(size.height/2),style=Stroke(width=w,pathEffect=PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(),3.dp.toPx()))))}
  fact->Modifier.border(1.5.dp,tint,RoundedCornerShape(999.dp))
  else->Modifier
 }
 Row(Modifier.background(bg,RoundedCornerShape(999.dp)).then(edge).padding(horizontal=11.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
  if(fact)SLIcon("check",Modifier.size(12.dp),tint) else if(estimate)SLIcon("activity",Modifier.size(12.dp),tint) else Box(Modifier.size(8.dp).background(tint,CircleShape));Text(text,fontSize=13.sp,color=tint,fontWeight=FontWeight.Bold,maxLines=1)
 }
}
private fun cardEdge(color:Color)=when(color){Color.White->Line;BrandSoft->Color(0xFFBCD3F7);OkSoft->Color(0xFFB7DEC8);PaleAmber->Color(0xFFF0D49A);DangerSoft->Color(0xFFF2B8B4);else->color}
@Composable fun SLCard(modifier:Modifier=Modifier,color:Color=Color.White,content:@Composable ColumnScope.()->Unit){Surface(modifier.fillMaxWidth(),color=color,shape=RoundedCornerShape(18.dp),border=BorderStroke(1.5.dp,cardEdge(color))) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)}}
// 줄로 나뉜 정보 카드 (와이어프레임 .card.flat)
@Composable fun FlatCard(rows:List<Pair<String,String>>){Surface(Modifier.fillMaxWidth(),color=Color.White,shape=RoundedCornerShape(18.dp),border=BorderStroke(1.5.dp,Line)){Column(Modifier.padding(horizontal=16.dp,vertical=4.dp)){rows.forEachIndexed{i,(label,value)->if(i>0)HorizontalDivider(color=Line);Box(Modifier.padding(vertical=12.dp)){DetailRow(label,value)}}}}}
// 주 버튼은 아래 4px 그림자가 있고 누르면 2px 내려간다. 장갑을 낀 손에서도 눌림이 보여야 한다.
@Composable fun SLButton(text:String,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,secondary:Boolean=false,icon:String?=null,color:Color=Brand,large:Boolean=false){
 val press=remember{MutableInteractionSource()};val pressed by press.collectIsPressedAsState();val drop=if(pressed&&enabled)2.dp else 0.dp
 val shape=RoundedCornerShape(16.dp);val fill=if(secondary)Color.White else if(enabled)color else Line;val ink=if(secondary)Ink else if(enabled)Color.White else Muted
 val shade=if(secondary||!enabled)Line else when(color){Danger->Color(0xFF8F1712);Ok->Color(0xFF0A5A3A);else->BrandDark}
 Box(modifier.fillMaxWidth().background(shade,shape)){
  Row(Modifier.padding(top=drop,bottom=4.dp-drop).fillMaxWidth().heightIn(min=if(large)76.dp else if(secondary)52.dp else 60.dp).background(fill,shape).then(if(secondary)Modifier.border(2.dp,Line,shape)else Modifier).clickable(press,null,enabled,role=Role.Button,onClick=onClick).padding(horizontal=20.dp,vertical=8.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){
   if(icon!=null){SLIcon(icon,Modifier.size(22.dp),ink);Spacer(Modifier.width(8.dp))};Text(text,fontSize=if(large)22.sp else if(secondary)17.sp else 19.sp,fontWeight=FontWeight.ExtraBold,color=ink,textAlign=TextAlign.Center)
  }
 }
}
@Composable fun GhostButton(text:String,onClick:()->Unit,enabled:Boolean=true,color:Color=Brand){TextButton(onClick,Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=enabled){Text(text,fontSize=17.sp,fontWeight=FontWeight.ExtraBold,color=color)}}
// 맞물린 두 고리. 작업자와 동료가 서로를 잡고 있다는 뜻
@Composable fun BrandRings(modifier:Modifier=Modifier,ringA:Color=Color(0xFFFFC20E),ringB:Color=Brand){Canvas(modifier){val r=size.height*.37f;val w=size.height*.13f;val y=size.height/2;drawCircle(ringA,r,Offset(size.width/2-r*.46f,y),style=Stroke(w));drawCircle(ringB,r,Offset(size.width/2+r*.46f,y),style=Stroke(w))}}
@Composable fun BrandMark(size:Int=28){Box(Modifier.size(size.dp).background(Brand,RoundedCornerShape((size*.3f).dp)),contentAlignment=Alignment.Center){BrandRings(Modifier.size(width=(size*.86f).dp,height=(size*.62f).dp),ringB=Color.White)}}
@Composable fun SLField(label:String,value:String,onValue:(String)->Unit,placeholder:String="",singleLine:Boolean=true,modifier:Modifier=Modifier){Column(verticalArrangement=Arrangement.spacedBy(6.dp),modifier=modifier){Text(label,fontSize=14.sp,fontWeight=FontWeight.Bold,color=Muted);OutlinedTextField(value,onValue,modifier=Modifier.fillMaxWidth().heightIn(min=54.dp),placeholder={Text(placeholder,fontSize=17.sp,color=Color(0xFF667384))},singleLine=singleLine,minLines=if(singleLine)1 else 3,shape=RoundedCornerShape(12.dp),colors=OutlinedTextFieldDefaults.colors(unfocusedBorderColor=Line,focusedBorderColor=Brand,unfocusedContainerColor=Color.White,focusedContainerColor=Color.White),textStyle=MaterialTheme.typography.bodyLarge.copy(fontSize=17.sp,color=Ink))}}
@Composable fun ScreenHead(title:String,lead:String?=null){Column(verticalArrangement=Arrangement.spacedBy(6.dp)){Text(title,fontSize=26.sp,lineHeight=34.sp,fontWeight=FontWeight.ExtraBold,color=Ink,letterSpacing=(-.5).sp);if(lead!=null)Text(lead,fontSize=16.sp,lineHeight=25.sp,color=Muted)}}
@Composable fun NoteText(text:String,center:Boolean=false){Text(text,Modifier.fillMaxWidth(),fontSize=14.sp,lineHeight=22.sp,color=Muted,textAlign=if(center)TextAlign.Center else TextAlign.Start)}
@Composable fun FlatBox(content:@Composable ColumnScope.()->Unit){Surface(Modifier.fillMaxWidth(),color=Color.White,shape=RoundedCornerShape(18.dp),border=BorderStroke(1.5.dp,Line)){Column(Modifier.padding(horizontal=16.dp,vertical=4.dp),content=content)}}
@Composable fun DetailPad(label:String,value:String){Box(Modifier.padding(vertical=12.dp)){DetailRow(label,value)}}
@Composable fun StepRow(text:String,chip:@Composable ()->Unit){Row(Modifier.fillMaxWidth().padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){Text(text,Modifier.weight(1f),fontSize=16.sp,lineHeight=23.sp,color=Ink);chip()}}
@Composable fun RadioRow(label:String,selected:Boolean,onClick:()->Unit){val shape=RoundedCornerShape(14.dp);Row(Modifier.fillMaxWidth().heightIn(min=56.dp).background(if(selected)BrandSoft else Color.White,shape).border(1.5.dp,if(selected)Brand else Line,shape).clickable(role=Role.RadioButton,onClick=onClick).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){Box(Modifier.size(22.dp).border(if(selected)7.dp else 2.dp,if(selected)Brand else Color(0xFF7A8896),CircleShape));Text(label,fontSize=17.sp,fontWeight=FontWeight.Bold,color=Ink)}}
@Composable fun PickRow(options:List<String>,selected:Int,onSelect:(Int)->Unit){Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){options.forEachIndexed{i,label->val on=i==selected;val shape=RoundedCornerShape(999.dp);Box(Modifier.heightIn(min=44.dp).background(if(on)BrandSoft else Color.White,shape).border(1.5.dp,if(on)Brand else Line,shape).clickable{onSelect(i)}.padding(horizontal=16.dp),contentAlignment=Alignment.Center){Text(label,fontSize=15.sp,fontWeight=FontWeight.Bold,color=if(on)Brand else Ink)}}}}
@Composable fun DoneMark(){Box(Modifier.size(88.dp).background(Ok,CircleShape),contentAlignment=Alignment.Center){SLIcon("check",Modifier.size(48.dp),Color.White)}}
// 시각표. 실선 점은 사람이 확인, 점선 점은 시스템이 추정
@Composable fun TimelineRow(time:String,title:String,detail:String?=null,estimate:Boolean=false,pending:Boolean=false,onClick:(()->Unit)?=null,extra:@Composable ColumnScope.()->Unit={}){Row(Modifier.fillMaxWidth().then(if(onClick!=null)Modifier.clickable(onClick=onClick)else Modifier).padding(vertical=9.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){
 Text(time,Modifier.widthIn(min=46.dp),fontSize=14.sp,fontWeight=FontWeight.Bold,color=Muted,maxLines=1,softWrap=false)
 Box(Modifier.padding(top=5.dp).size(12.dp).then(if(estimate)Modifier.drawBehind{val w=2.dp.toPx();drawCircle(Amber,size.minDimension/2-w/2,style=Stroke(width=w,pathEffect=PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(),2.dp.toPx()))))}else Modifier.background(if(pending)Color(0xFF7A8896)else Brand,CircleShape)))
 Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(title,fontSize=16.sp,lineHeight=22.sp,fontWeight=FontWeight.Bold,color=Ink);if(detail!=null)Text(detail,fontSize=14.sp,lineHeight=21.sp,color=Muted);extra()}
}}
@Composable fun TimelineLegend(){Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){SLPill("사람이 확인",fact=true);SLPill("시스템이 추정",amber=true,estimate=true)}}
// 체험 모드 전용 약도. 실제 위치가 아니다.
@Composable fun SiteSketch(name:String){Column(Modifier.fillMaxWidth().height(200.dp).background(Color(0xFFDDE4EC),RoundedCornerShape(14.dp)),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Canvas(Modifier.size(width=96.dp,height=64.dp)){val a=Offset(size.width*.2f,size.height*.75f);val b=Offset(size.width*.8f,size.height*.25f);drawLine(Muted,a,b,3.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(8f,10f)));drawCircle(Brand,8.dp.toPx(),a);drawCircle(Danger,8.dp.toPx(),b)};Spacer(Modifier.height(8.dp));Text("현장 약도 · 체험 예시",fontSize=14.sp,fontWeight=FontWeight.Bold,color=Muted);Text("파랑: 내 위치 · 빨강: $name 님",fontSize=13.sp,color=Muted)}}
@Composable fun ScreenTitle(title:String){Text(title,fontSize=24.sp,lineHeight=32.sp,color=Ink,fontWeight=FontWeight.ExtraBold,letterSpacing=(-.5).sp)}
@Composable fun PageHeading(eyebrow:String,title:String,subtitle:String?=null){Column(verticalArrangement=Arrangement.spacedBy(4.dp)){Text(eyebrow,fontSize=14.sp,color=Muted,fontWeight=FontWeight.Bold);Text(title,fontSize=26.sp,lineHeight=34.sp,color=Ink,fontWeight=FontWeight.ExtraBold,letterSpacing=(-.5).sp);if(subtitle!=null)Text(subtitle,fontSize=16.sp,lineHeight=25.sp,color=Muted)}}
@Composable fun SectionHeading(title:String,action:String?=null,onAction:()->Unit={}){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){Text(title,fontSize=18.sp,fontWeight=FontWeight.ExtraBold,color=Ink);if(action!=null)TextButton(onAction,contentPadding=PaddingValues(horizontal=0.dp)){Text(action,fontSize=14.sp,color=Muted);Spacer(Modifier.width(3.dp));SLIcon("chevron",Modifier.size(14.dp),Muted)}}}
@Composable fun Avatar(name:String,size:Int=44){Box(Modifier.size(size.dp).background(Color(0xFFDCE6F5),CircleShape),contentAlignment=Alignment.Center){Text(name.take(1),fontSize=(size*.4).sp,fontWeight=FontWeight.ExtraBold,color=Brand)}}
@Composable fun SmallNote(text:String,amber:Boolean=false){Row(Modifier.fillMaxWidth().background(if(amber)PaleAmber else BrandSoft,RoundedCornerShape(12.dp)).padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
 SLIcon(if(amber)"report" else "info",Modifier.size(16.dp),if(amber)Amber else Brand)
 Text(text,fontSize=14.sp,lineHeight=21.sp,color=if(amber)Amber else Brand)
}}
@Composable fun DetailRow(label:String,value:String){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically){Text(label,fontSize=16.sp,color=Muted);Text(value,Modifier.weight(1f),fontSize=16.sp,fontWeight=FontWeight.ExtraBold,color=Ink,maxLines=2,textAlign=TextAlign.End)}}
@Composable fun MenuRow(icon:String,title:String,subtitle:String?=null,onClick:()->Unit){Row(Modifier.fillMaxWidth().heightIn(min=62.dp).clickable(onClick=onClick).padding(vertical=9.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
 Box(Modifier.size(40.dp).background(CanvasColor,RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center){SLIcon(icon,Modifier.size(20.dp))}
 Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(title,fontSize=17.sp,fontWeight=FontWeight.Bold,color=Ink);if(subtitle!=null)Text(subtitle,fontSize=14.sp,color=Muted,lineHeight=21.sp)}
 SLIcon("chevron",Modifier.size(17.dp),Muted)
}}
@Composable fun EmptyState(icon:String,title:String,detail:String){Column(Modifier.fillMaxWidth().padding(vertical=22.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){Box(Modifier.size(48.dp).background(CanvasColor,CircleShape),contentAlignment=Alignment.Center){SLIcon(icon,tint=Muted)};Text(title,fontSize=15.sp,fontWeight=FontWeight.SemiBold,color=Ink);Text(detail,fontSize=14.sp,lineHeight=21.sp,color=Muted)}}
@Composable fun PhotoPreview(path:String,modifier:Modifier=Modifier){val bitmap=remember(path){BitmapFactory.decodeFile(path,BitmapFactory.Options().apply{inSampleSize=3})};if(bitmap!=null)Image(bitmap.asImageBitmap(),"촬영한 현장 주변 사진",modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(14.dp)),contentScale=ContentScale.Crop)}
@Composable fun PhotoCapture(path:String?,title:String="현장 사진 촬영",hint:String="위험 위치와 주변이 잘 보이게 찍어 주세요.",onCapture:()->Unit){if(path!=null){PhotoPreview(reviewPathOf(path));NoteText("기록에 저장할 때 얼굴은 자동으로 가려요.");TextButton(onCapture,Modifier.fillMaxWidth()){SLIcon("camera",Modifier.size(16.dp));Spacer(Modifier.width(6.dp));Text("사진 다시 촬영",fontSize=14.sp)}}else Surface(Modifier.fillMaxWidth().height(160.dp).clickable(onClick=onCapture),color=CanvasColor,shape=RoundedCornerShape(16.dp),border=BorderStroke(1.dp,Line)){
 Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Box(Modifier.size(44.dp).background(BrandSoft,CircleShape),contentAlignment=Alignment.Center){SLIcon("camera")};Spacer(Modifier.height(10.dp));Text(title,fontSize=15.sp,fontWeight=FontWeight.Bold,color=Brand);Spacer(Modifier.height(5.dp));Text(hint,fontSize=13.sp,color=Muted)}
}}
// 경고 테이프. 긴급 화면에만 쓴다.
@Composable fun TapeStrip(){Canvas(Modifier.fillMaxWidth().height(16.dp)){drawRect(Color(0xFFFFC20E));val band=14.dp.toPx();var x=-size.height;while(x<size.width){drawPath(Path().apply{moveTo(x,size.height);lineTo(x+size.height,0f);lineTo(x+size.height+band,0f);lineTo(x+band,size.height);close()},Ink);x+=band*2}}}
@Composable fun EmergencyStrip(onDial:()->Unit){Row(Modifier.fillMaxWidth().height(56.dp).background(Danger).clickable(onClick=onDial).padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
 Text("긴급 상황에는",fontSize=16.sp,fontWeight=FontWeight.Bold,color=Color.White)
 Box(Modifier.background(Color.White,RoundedCornerShape(12.dp)).padding(horizontal=16.dp,vertical=9.dp)){Text("119 전화",fontSize=16.sp,fontWeight=FontWeight.ExtraBold,color=Danger)}
}}
