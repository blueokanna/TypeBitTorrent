package com.typebit.ui.components

// Auto-generated from the bundled flag PNGs (flagcdn.com,
// national flags, public domain). Maps ISO-3166 alpha-2 country
// codes to their flag drawable; unknown/private ranges return null
// and the caller falls back to a globe icon.
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import org.jetbrains.compose.resources.painterResource
import typebittorrent.composeapp.generated.resources.Res
import typebittorrent.composeapp.generated.resources.flag_ae
import typebittorrent.composeapp.generated.resources.flag_al
import typebittorrent.composeapp.generated.resources.flag_am
import typebittorrent.composeapp.generated.resources.flag_ao
import typebittorrent.composeapp.generated.resources.flag_ar
import typebittorrent.composeapp.generated.resources.flag_at
import typebittorrent.composeapp.generated.resources.flag_au
import typebittorrent.composeapp.generated.resources.flag_az
import typebittorrent.composeapp.generated.resources.flag_ba
import typebittorrent.composeapp.generated.resources.flag_bd
import typebittorrent.composeapp.generated.resources.flag_be
import typebittorrent.composeapp.generated.resources.flag_bg
import typebittorrent.composeapp.generated.resources.flag_bn
import typebittorrent.composeapp.generated.resources.flag_bo
import typebittorrent.composeapp.generated.resources.flag_br
import typebittorrent.composeapp.generated.resources.flag_by
import typebittorrent.composeapp.generated.resources.flag_ca
import typebittorrent.composeapp.generated.resources.flag_cd
import typebittorrent.composeapp.generated.resources.flag_ch
import typebittorrent.composeapp.generated.resources.flag_ci
import typebittorrent.composeapp.generated.resources.flag_cl
import typebittorrent.composeapp.generated.resources.flag_cm
import typebittorrent.composeapp.generated.resources.flag_cn
import typebittorrent.composeapp.generated.resources.flag_co
import typebittorrent.composeapp.generated.resources.flag_cr
import typebittorrent.composeapp.generated.resources.flag_cu
import typebittorrent.composeapp.generated.resources.flag_cy
import typebittorrent.composeapp.generated.resources.flag_cz
import typebittorrent.composeapp.generated.resources.flag_de
import typebittorrent.composeapp.generated.resources.flag_dk
import typebittorrent.composeapp.generated.resources.flag_do
import typebittorrent.composeapp.generated.resources.flag_dz
import typebittorrent.composeapp.generated.resources.flag_ec
import typebittorrent.composeapp.generated.resources.flag_ee
import typebittorrent.composeapp.generated.resources.flag_eg
import typebittorrent.composeapp.generated.resources.flag_es
import typebittorrent.composeapp.generated.resources.flag_et
import typebittorrent.composeapp.generated.resources.flag_fi
import typebittorrent.composeapp.generated.resources.flag_fr
import typebittorrent.composeapp.generated.resources.flag_gb
import typebittorrent.composeapp.generated.resources.flag_ge
import typebittorrent.composeapp.generated.resources.flag_gh
import typebittorrent.composeapp.generated.resources.flag_gr
import typebittorrent.composeapp.generated.resources.flag_hk
import typebittorrent.composeapp.generated.resources.flag_hr
import typebittorrent.composeapp.generated.resources.flag_ht
import typebittorrent.composeapp.generated.resources.flag_hu
import typebittorrent.composeapp.generated.resources.flag_id
import typebittorrent.composeapp.generated.resources.flag_ie
import typebittorrent.composeapp.generated.resources.flag_il
import typebittorrent.composeapp.generated.resources.flag_in
import typebittorrent.composeapp.generated.resources.flag_ir
import typebittorrent.composeapp.generated.resources.flag_is
import typebittorrent.composeapp.generated.resources.flag_it
import typebittorrent.composeapp.generated.resources.flag_jm
import typebittorrent.composeapp.generated.resources.flag_jo
import typebittorrent.composeapp.generated.resources.flag_jp
import typebittorrent.composeapp.generated.resources.flag_ke
import typebittorrent.composeapp.generated.resources.flag_kh
import typebittorrent.composeapp.generated.resources.flag_kr
import typebittorrent.composeapp.generated.resources.flag_kw
import typebittorrent.composeapp.generated.resources.flag_kz
import typebittorrent.composeapp.generated.resources.flag_la
import typebittorrent.composeapp.generated.resources.flag_lb
import typebittorrent.composeapp.generated.resources.flag_lk
import typebittorrent.composeapp.generated.resources.flag_lt
import typebittorrent.composeapp.generated.resources.flag_lu
import typebittorrent.composeapp.generated.resources.flag_lv
import typebittorrent.composeapp.generated.resources.flag_ma
import typebittorrent.composeapp.generated.resources.flag_md
import typebittorrent.composeapp.generated.resources.flag_mk
import typebittorrent.composeapp.generated.resources.flag_mm
import typebittorrent.composeapp.generated.resources.flag_mt
import typebittorrent.composeapp.generated.resources.flag_mx
import typebittorrent.composeapp.generated.resources.flag_my
import typebittorrent.composeapp.generated.resources.flag_mz
import typebittorrent.composeapp.generated.resources.flag_ng
import typebittorrent.composeapp.generated.resources.flag_nl
import typebittorrent.composeapp.generated.resources.flag_no
import typebittorrent.composeapp.generated.resources.flag_np
import typebittorrent.composeapp.generated.resources.flag_nz
import typebittorrent.composeapp.generated.resources.flag_om
import typebittorrent.composeapp.generated.resources.flag_pa
import typebittorrent.composeapp.generated.resources.flag_pe
import typebittorrent.composeapp.generated.resources.flag_ph
import typebittorrent.composeapp.generated.resources.flag_pk
import typebittorrent.composeapp.generated.resources.flag_pl
import typebittorrent.composeapp.generated.resources.flag_pt
import typebittorrent.composeapp.generated.resources.flag_py
import typebittorrent.composeapp.generated.resources.flag_qa
import typebittorrent.composeapp.generated.resources.flag_ro
import typebittorrent.composeapp.generated.resources.flag_rs
import typebittorrent.composeapp.generated.resources.flag_ru
import typebittorrent.composeapp.generated.resources.flag_sa
import typebittorrent.composeapp.generated.resources.flag_se
import typebittorrent.composeapp.generated.resources.flag_sg
import typebittorrent.composeapp.generated.resources.flag_si
import typebittorrent.composeapp.generated.resources.flag_sk
import typebittorrent.composeapp.generated.resources.flag_sn
import typebittorrent.composeapp.generated.resources.flag_th
import typebittorrent.composeapp.generated.resources.flag_tn
import typebittorrent.composeapp.generated.resources.flag_tr
import typebittorrent.composeapp.generated.resources.flag_tw
import typebittorrent.composeapp.generated.resources.flag_ua
import typebittorrent.composeapp.generated.resources.flag_us
import typebittorrent.composeapp.generated.resources.flag_uy
import typebittorrent.composeapp.generated.resources.flag_uz
import typebittorrent.composeapp.generated.resources.flag_ve
import typebittorrent.composeapp.generated.resources.flag_vn
import typebittorrent.composeapp.generated.resources.flag_za
import typebittorrent.composeapp.generated.resources.flag_zw

/**
 * The bundled flag painter for a country code, or null when the
 * country has no bundled asset (falls back to a globe icon).
 */
@Composable
fun flagPainter(cc: String): Painter? = when (cc.uppercase()) {
    "AE" -> painterResource(Res.drawable.flag_ae)
    "AL" -> painterResource(Res.drawable.flag_al)
    "AM" -> painterResource(Res.drawable.flag_am)
    "AO" -> painterResource(Res.drawable.flag_ao)
    "AR" -> painterResource(Res.drawable.flag_ar)
    "AT" -> painterResource(Res.drawable.flag_at)
    "AU" -> painterResource(Res.drawable.flag_au)
    "AZ" -> painterResource(Res.drawable.flag_az)
    "BA" -> painterResource(Res.drawable.flag_ba)
    "BD" -> painterResource(Res.drawable.flag_bd)
    "BE" -> painterResource(Res.drawable.flag_be)
    "BG" -> painterResource(Res.drawable.flag_bg)
    "BN" -> painterResource(Res.drawable.flag_bn)
    "BO" -> painterResource(Res.drawable.flag_bo)
    "BR" -> painterResource(Res.drawable.flag_br)
    "BY" -> painterResource(Res.drawable.flag_by)
    "CA" -> painterResource(Res.drawable.flag_ca)
    "CD" -> painterResource(Res.drawable.flag_cd)
    "CH" -> painterResource(Res.drawable.flag_ch)
    "CI" -> painterResource(Res.drawable.flag_ci)
    "CL" -> painterResource(Res.drawable.flag_cl)
    "CM" -> painterResource(Res.drawable.flag_cm)
    "CN" -> painterResource(Res.drawable.flag_cn)
    "CO" -> painterResource(Res.drawable.flag_co)
    "CR" -> painterResource(Res.drawable.flag_cr)
    "CU" -> painterResource(Res.drawable.flag_cu)
    "CY" -> painterResource(Res.drawable.flag_cy)
    "CZ" -> painterResource(Res.drawable.flag_cz)
    "DE" -> painterResource(Res.drawable.flag_de)
    "DK" -> painterResource(Res.drawable.flag_dk)
    "DO" -> painterResource(Res.drawable.flag_do)
    "DZ" -> painterResource(Res.drawable.flag_dz)
    "EC" -> painterResource(Res.drawable.flag_ec)
    "EE" -> painterResource(Res.drawable.flag_ee)
    "EG" -> painterResource(Res.drawable.flag_eg)
    "ES" -> painterResource(Res.drawable.flag_es)
    "ET" -> painterResource(Res.drawable.flag_et)
    "FI" -> painterResource(Res.drawable.flag_fi)
    "FR" -> painterResource(Res.drawable.flag_fr)
    "GB" -> painterResource(Res.drawable.flag_gb)
    "GE" -> painterResource(Res.drawable.flag_ge)
    "GH" -> painterResource(Res.drawable.flag_gh)
    "GR" -> painterResource(Res.drawable.flag_gr)
    "HK" -> painterResource(Res.drawable.flag_hk)
    "HR" -> painterResource(Res.drawable.flag_hr)
    "HT" -> painterResource(Res.drawable.flag_ht)
    "HU" -> painterResource(Res.drawable.flag_hu)
    "ID" -> painterResource(Res.drawable.flag_id)
    "IE" -> painterResource(Res.drawable.flag_ie)
    "IL" -> painterResource(Res.drawable.flag_il)
    "IN" -> painterResource(Res.drawable.flag_in)
    "IR" -> painterResource(Res.drawable.flag_ir)
    "IS" -> painterResource(Res.drawable.flag_is)
    "IT" -> painterResource(Res.drawable.flag_it)
    "JM" -> painterResource(Res.drawable.flag_jm)
    "JO" -> painterResource(Res.drawable.flag_jo)
    "JP" -> painterResource(Res.drawable.flag_jp)
    "KE" -> painterResource(Res.drawable.flag_ke)
    "KH" -> painterResource(Res.drawable.flag_kh)
    "KR" -> painterResource(Res.drawable.flag_kr)
    "KW" -> painterResource(Res.drawable.flag_kw)
    "KZ" -> painterResource(Res.drawable.flag_kz)
    "LA" -> painterResource(Res.drawable.flag_la)
    "LB" -> painterResource(Res.drawable.flag_lb)
    "LK" -> painterResource(Res.drawable.flag_lk)
    "LT" -> painterResource(Res.drawable.flag_lt)
    "LU" -> painterResource(Res.drawable.flag_lu)
    "LV" -> painterResource(Res.drawable.flag_lv)
    "MA" -> painterResource(Res.drawable.flag_ma)
    "MD" -> painterResource(Res.drawable.flag_md)
    "MK" -> painterResource(Res.drawable.flag_mk)
    "MM" -> painterResource(Res.drawable.flag_mm)
    "MT" -> painterResource(Res.drawable.flag_mt)
    "MX" -> painterResource(Res.drawable.flag_mx)
    "MY" -> painterResource(Res.drawable.flag_my)
    "MZ" -> painterResource(Res.drawable.flag_mz)
    "NG" -> painterResource(Res.drawable.flag_ng)
    "NL" -> painterResource(Res.drawable.flag_nl)
    "NO" -> painterResource(Res.drawable.flag_no)
    "NP" -> painterResource(Res.drawable.flag_np)
    "NZ" -> painterResource(Res.drawable.flag_nz)
    "OM" -> painterResource(Res.drawable.flag_om)
    "PA" -> painterResource(Res.drawable.flag_pa)
    "PE" -> painterResource(Res.drawable.flag_pe)
    "PH" -> painterResource(Res.drawable.flag_ph)
    "PK" -> painterResource(Res.drawable.flag_pk)
    "PL" -> painterResource(Res.drawable.flag_pl)
    "PT" -> painterResource(Res.drawable.flag_pt)
    "PY" -> painterResource(Res.drawable.flag_py)
    "QA" -> painterResource(Res.drawable.flag_qa)
    "RO" -> painterResource(Res.drawable.flag_ro)
    "RS" -> painterResource(Res.drawable.flag_rs)
    "RU" -> painterResource(Res.drawable.flag_ru)
    "SA" -> painterResource(Res.drawable.flag_sa)
    "SE" -> painterResource(Res.drawable.flag_se)
    "SG" -> painterResource(Res.drawable.flag_sg)
    "SI" -> painterResource(Res.drawable.flag_si)
    "SK" -> painterResource(Res.drawable.flag_sk)
    "SN" -> painterResource(Res.drawable.flag_sn)
    "TH" -> painterResource(Res.drawable.flag_th)
    "TN" -> painterResource(Res.drawable.flag_tn)
    "TR" -> painterResource(Res.drawable.flag_tr)
    "TW" -> painterResource(Res.drawable.flag_tw)
    "UA" -> painterResource(Res.drawable.flag_ua)
    "US" -> painterResource(Res.drawable.flag_us)
    "UY" -> painterResource(Res.drawable.flag_uy)
    "UZ" -> painterResource(Res.drawable.flag_uz)
    "VE" -> painterResource(Res.drawable.flag_ve)
    "VN" -> painterResource(Res.drawable.flag_vn)
    "ZA" -> painterResource(Res.drawable.flag_za)
    "ZW" -> painterResource(Res.drawable.flag_zw)
    else -> null
}
