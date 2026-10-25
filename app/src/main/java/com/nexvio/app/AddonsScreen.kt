package com.nexvio.app
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch
private val AddonPanel=Color(0xFF1B1D22)
private val AddonLime=Color(0xFFB7F36B)
private val AddonMuted=Color(0xFF9A9DA6)

@Composable internal fun AddonsScreen(onBack:()->Unit) {
 val context=LocalContext.current;val repo=remember(context){AddonRepository(context)};val scope=rememberCoroutineScope()
 var addons by remember { mutableStateOf(repo.getInstalled()) };var url by remember { mutableStateOf("") };var loading by remember { mutableStateOf(false) }
 var checking by remember { mutableStateOf<String?>(null) };var error by remember { mutableStateOf<String?>(null) };var notice by remember { mutableStateOf<String?>(null) }
 var removeTarget by remember { mutableStateOf<InstalledAddon?>(null) };var expanded by remember { mutableStateOf(emptySet<String>()) }
 LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
  item { Row(verticalAlignment=Alignment.CenterVertically) { Text("‹ Back",color=AddonLime,modifier=Modifier.clickable(onClick=onBack));Spacer(Modifier.width(18.dp));Column { Text("Add-ons",fontSize=28.sp,fontWeight=FontWeight.ExtraBold);Text("Compatible catalogue and stream providers",color=AddonMuted,fontSize=12.sp) } } }
  item { Column(Modifier.fillMaxWidth().background(AddonPanel,RoundedCornerShape(16.dp)).padding(16.dp)) {
   Text("Install an add-on",fontWeight=FontWeight.Bold,fontSize=18.sp);Text("Enter a provider's HTTP or HTTPS manifest URL. Nexvio reads advertised capabilities and never executes downloaded code.",color=AddonMuted,fontSize=13.sp,modifier=Modifier.padding(top=6.dp))
   OutlinedTextField(url,{url=it;error=null;notice=null},Modifier.fillMaxWidth().padding(top=8.dp),singleLine=true,label={Text("Manifest URL")},placeholder={Text("https://provider.example/manifest.json")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),enabled=!loading)
   Button(onClick={ loading=true;error=null;notice=null;scope.launch { try { val a=repo.install(url.trim());addons=repo.getInstalled();url="";notice="${a.manifest.name} installed successfully." } catch(e:Exception) { error=e.message?:"Could not install add-on." } finally { loading=false } } },enabled=!loading&&url.isNotBlank(),modifier=Modifier.fillMaxWidth().padding(top=8.dp)) {
    if(loading){CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp);Spacer(Modifier.width(8.dp))};Text(if(loading)"Checking manifest…" else "Add add-on")
   }
   error?.let { Text(it,color=MaterialTheme.colorScheme.error,fontSize=13.sp,modifier=Modifier.padding(top=8.dp)) };notice?.let { Text(it,color=AddonLime,fontSize=13.sp,modifier=Modifier.padding(top=8.dp)) }
  } }
  item { Text("INSTALLED (${addons.size})",color=AddonMuted,fontSize=11.sp,letterSpacing=1.5.sp) }
  if(addons.isEmpty()) item { Column(Modifier.fillMaxWidth().background(AddonPanel,RoundedCornerShape(16.dp)).padding(18.dp)) { Text("No add-ons installed",fontWeight=FontWeight.Bold,fontSize=17.sp);Text("Install a compatible manifest to save its capabilities here. Catalogue and stream queries are part of later phases.",color=AddonMuted,fontSize=13.sp,modifier=Modifier.padding(top=6.dp)) } }
  items(addons,key={it.manifest.id}) { a ->
   val open=a.manifest.id in expanded
   Column(Modifier.fillMaxWidth().background(AddonPanel,RoundedCornerShape(16.dp)).padding(16.dp)) {
    Row(verticalAlignment=Alignment.Top) {
     Column(Modifier.weight(1f)) { Text(a.manifest.name,fontWeight=FontWeight.Bold,fontSize=17.sp);Text("v${a.manifest.version} • ${a.manifest.id}",color=AddonMuted,fontSize=11.sp)
      Text(when { checking==a.manifest.id->"Checking connection…";a.lastError!=null->"Connection error";a.lastSuccessfulConnectionAtMillis!=null->"Last connection successful";else->"Not tested yet" },color=if(a.lastError!=null)MaterialTheme.colorScheme.error else AddonLime,fontSize=12.sp)
     }
     Column(horizontalAlignment=Alignment.CenterHorizontally) { Switch(a.enabled,{repo.setEnabled(a.manifest.id,it);addons=repo.getInstalled()});Text(if(a.enabled)"Enabled" else "Disabled",color=AddonMuted,fontSize=10.sp) }
    }
    if(a.manifest.description.isNotBlank()) Text(a.manifest.description,color=AddonMuted,fontSize=13.sp,modifier=Modifier.padding(top=6.dp))
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(2.dp)) {
     TextButton(onClick={expanded=if(open)expanded-a.manifest.id else expanded+a.manifest.id}) { Text(if(open)"Hide details" else "Details") }
     TextButton(enabled=checking==null,onClick={checking=a.manifest.id;error=null;notice=null;scope.launch { try { repo.testConnection(a);addons=repo.getInstalled();notice="Connection succeeded." } catch(e:AddonConnectionException) { addons=repo.getInstalled();error="${a.manifest.name}: ${e.message}" } catch(e:Exception) { error=e.message?:"Connection test failed." } finally { checking=null } }}) { Text(if(checking==a.manifest.id)"Testing…" else "Test connection") }
     TextButton(onClick={removeTarget=a}) { Text("Remove",color=MaterialTheme.colorScheme.error) }
    }
    a.lastError?.let { Text("Last error: $it",color=MaterialTheme.colorScheme.error,fontSize=12.sp) }
    a.lastCheckedAtMillis?.let { Text("Last checked: ${DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(it))}",color=AddonMuted,fontSize=11.sp) }
    if(open) {
     HorizontalDivider(modifier=Modifier.padding(vertical=8.dp),color=Color(0xFF101114))
     Text("Manifest: ${a.manifestUrl}",color=AddonMuted,fontSize=11.sp);Text("Types: ${a.manifest.types.joinToString()}",color=Color.White,fontSize=12.sp)
     Text("Resources: ${a.manifest.resources.joinToString { it.name }}",color=Color.White,fontSize=12.sp);Text("Catalogues: ${a.manifest.catalogs.size}",color=Color.White,fontSize=12.sp)
     a.manifest.resources.forEach { r->Text("• ${r.name}${if(r.types.isEmpty()) "" else " — types: ${r.types.joinToString()}"}${if(r.idPrefixes.isEmpty()) "" else " — ID prefixes: ${r.idPrefixes.joinToString()}"}",color=AddonMuted,fontSize=11.sp) }
     a.manifest.catalogs.take(20).forEach { c->Text("• ${c.type} / ${c.name} (${c.id})${if(c.extraFields.isEmpty()) "" else " — filters: ${c.extraFields.joinToString()}"}",color=AddonMuted,fontSize=11.sp) }
    }
   }
  }
  item { Text("Add-ons are untrusted network services. Nexvio stores manifest metadata locally and does not execute provider code or bypass authentication or DRM.",color=AddonMuted,fontSize=11.sp) }
 }
 removeTarget?.let { a->AlertDialog(onDismissRequest={removeTarget=null},title={Text("Remove ${a.manifest.name}?")},text={Text("This removes its saved manifest information from this device.")},confirmButton={TextButton(onClick={repo.remove(a.manifest.id);addons=repo.getInstalled();removeTarget=null;notice="${a.manifest.name} removed.";error=null}){Text("Remove",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(onClick={removeTarget=null}){Text("Cancel")}}) }
}