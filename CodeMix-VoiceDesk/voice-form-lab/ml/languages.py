"""Synthetic, not native-speaker-validated language fixtures and conservative hints."""
import re

LANGUAGES = {'auto': 'Auto detect', 'ta': 'Tamil', 'te': 'Telugu', 'kn': 'Kannada',
             'ml': 'Malayalam', 'en': 'English', 'hi': 'Hindi'}
SOUTH = {
 'ta': {
  'water': ('தண்ணீர்', 'குடிநீர்', 'தண்ணீர் வரவில்லை', 'thanneer', 'kudineer', 'thanneer varavillai'),
  'electricity': ('மின்சாரம்', 'மின்விளக்கு', 'மின்சாரம் இல்லை', 'minsaram', 'minvilakku', 'minsaram illai'),
  'roads': ('சாலை', 'குழி', 'சாலையில் குழி உள்ளது', 'saalai', 'kuzhi', 'saalaiyil kuzhi ullathu'),
  'sanitation': ('குப்பை', 'சாக்கடை', 'குப்பை அகற்றப்படவில்லை', 'kuppai', 'saakkadai', 'kuppai agatrapadavillai')},
 'te': {
  'water': ('నీరు', 'నీటి', 'నీరు రావడం లేదు', 'neeru', 'neeti', 'neeru raavadam ledu'),
  'electricity': ('కరెంట్', 'విద్యుత్', 'విద్యుత్ సరఫరా లేదు', 'currentu', 'vidyut', 'currentu ledu'),
  'roads': ('రోడ్డు', 'రహదారి', 'రోడ్డుపై గుంత ఉంది', 'roddu', 'rahadaari', 'roddu pai guntha undi'),
  'sanitation': ('చెత్త', 'కాలువ', 'చెత్త తీయలేదు', 'chetta', 'kaaluva', 'chetta teeyaledu')},
 'kn': {
  'water': ('ನೀರು', 'ನೀರಿನ', 'ನೀರು ಬರುತ್ತಿಲ್ಲ', 'neeru', 'neerina', 'neeru baruttilla'),
  'electricity': ('ವಿದ್ಯುತ್', 'ದೀಪ', 'ವಿದ್ಯುತ್ ಇಲ್ಲ', 'vidyut', 'deepa', 'vidyut illa'),
  'roads': ('ರಸ್ತೆ', 'ಗುಂಡಿ', 'ರಸ್ತೆಯಲ್ಲಿ ಗುಂಡಿ ಇದೆ', 'raste', 'gundi', 'rasteyalli gundi ide'),
  'sanitation': ('ಕಸ', 'ಚರಂಡಿ', 'ಕಸ ತೆಗೆದಿಲ್ಲ', 'kasa', 'charandi', 'kasa tegedilla')},
 'ml': {
  'water': ('വെള്ളം', 'കുടിവെള്ള', 'വെള്ളം വരുന്നില്ല', 'vellam', 'kudivellam', 'vellam varunnilla'),
  'electricity': ('വൈദ്യുതി', 'തെരുവുവിളക്ക്', 'വൈദ്യുതി ഇല്ല', 'vaidyuthi', 'theruvuvilakku', 'vaidyuthi illa'),
  'roads': ('റോഡ്', 'കുഴി', 'റോഡിൽ കുഴിയുണ്ട്', 'roadil', 'kuzhi', 'roadil kuzhi undu'),
  'sanitation': ('മാലിന്യം', 'ഓട', 'മാലിന്യം നീക്കിയില്ല', 'maalinyam', 'oda', 'maalinyam neekkiyilla')}
}

def vocabulary():
    return [word for categories in SOUTH.values() for row in categories.values() for word in (row[0], row[1], row[3], row[4])]

def language_hints(text):
    hints = []
    for code, start, end in [('ta', 0xB80, 0xBFF), ('te', 0xC00, 0xC7F), ('kn', 0xC80, 0xCFF), ('ml', 0xD00, 0xD7F)]:
        if any(start <= ord(c) <= end for c in text): hints.append(code)
    # Romanized words overlap between languages: return candidate hints, not certainty.
    for code, categories in SOUTH.items():
        if any(re.search(r'\b'+re.escape(word)+r'\b', text, re.I) for row in categories.values() for word in row[3:5]):
            hints.append(code+'-Latn')
    if re.search(r'\b(paani|bijli|sadak|kachra|naali|hai|nahi)\b', text, re.I): hints.append('hi-Latn')
    if re.search(r'\b(at|near|since|water|power|road|garbage|please|issue|name|id)\b', text, re.I): hints.append('en')
    return hints or ['und']

def south_datasets():
    training, examples = [], []
    for code, categories in SOUTH.items():
        for category, row in categories.items():
            for word in (row[0], row[1], row[3], row[4]):
                training.append({'text': word+' issue reported', 'category':category, 'language':code})
            for script, phrase in [('native',row[2]), ('Latn',row[5])]:
                text=f'{phrase} at Demo {code.upper()} Lane since 2026-01-15. ID: SYN-{code.upper()}-01.'
                examples.append({'id':f'south-{code}-{script}-{category}', 'synthetic':True,
                    'language':code+'-en' if script=='native' else code+'-Latn-en',
                    'reference_transcript':text,'observed_transcript':text,
                    'fields':{'category':category,'summary':text.split('.')[0], 'location':f'Demo {code.upper()} Lane',
                              'datetime':'2026-01-15','identifier':f'SYN-{code.upper()}-01'}})
    return ({'version':'synthetic-south-train-v1','synthetic':True,'examples':training},
            {'version':'synthetic-south-eval-v1','synthetic':True,'examples':examples})
