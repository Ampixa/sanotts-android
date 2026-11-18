# SPDX-License-Identifier: GPL-3.0-or-later
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

SNT := ../../../../third_party/sanotts/mcu
ESPEAK := $(SNT)/ports/wasm/espeak

# Same espeak-ng translator sources as upstream mcu/ports/wasm/build_g2p.sh
# and tests/host_test.py.
LIBESPEAK := common.c mnemonics.c error.c ieee80.c compiledata.c compiledict.c \
    dictionary.c encoding.c intonation.c langopts.c numbers.c phoneme.c \
    phonemelist.c readclause.c setlengths.c soundicon.c spect.c ssml.c \
    synthdata.c synthesize.c tr_languages.c translate.c translateword.c \
    voices.c wavegen.c speech.c espeak_api.c
UCD := case.c categories.c ctype.c proplist.c scripts.c tostring.c

LOCAL_MODULE := sanotts_android
LOCAL_SRC_FILES := sanotts_android.c \
    $(SNT)/src/snt_front_f32.c \
    $(SNT)/src/snt_piperlite.c \
    $(SNT)/ports/wasm/snt_voice_wasm.c \
    $(addprefix $(ESPEAK)/libespeak-ng/,$(LIBESPEAK)) \
    $(addprefix $(ESPEAK)/ucd-tools/,$(UCD))
# snt_g2p_wasm.c is #included by sanotts_android.c, not listed here.
LOCAL_C_INCLUDES := $(LOCAL_PATH) \
    $(LOCAL_PATH)/$(SNT)/include \
    $(LOCAL_PATH)/$(SNT)/ports/wasm \
    $(LOCAL_PATH)/$(ESPEAK) \
    $(LOCAL_PATH)/$(ESPEAK)/include \
    $(LOCAL_PATH)/$(ESPEAK)/ucd-tools/include \
    $(LOCAL_PATH)/$(ESPEAK)/libespeak-ng
LOCAL_CFLAGS := -O2 -std=gnu11 -w -DHAVE_CONFIG_H

include $(BUILD_SHARED_LIBRARY)
