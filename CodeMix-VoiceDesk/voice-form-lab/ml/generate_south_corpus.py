import json
from pathlib import Path
from languages import south_datasets

if __name__ == '__main__':
    data_dir=Path(__file__).parent/'data'
    for name, data in zip(('south-train','south-eval'), south_datasets()):
        (Path(__file__).parent/'data'/f'{name}.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    legacy=json.loads((data_dir/'eval.json').read_text(encoding='utf-8'))
    combined={'version':'synthetic-eval-v2-south','synthetic':True,'examples':legacy['examples']+south_datasets()[1]['examples']}
    (data_dir/'evaluation-v2.json').write_text(json.dumps(combined,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
