from pathlib import Path
import argparse,ipaddress,socket,subprocess
def main():
 p=argparse.ArgumentParser();p.add_argument('project',type=Path);p.add_argument('--rds-host',required=True);p.add_argument('--output',required=True);p.add_argument('--image',required=True);p.add_argument('--payments',choices=['true','false'],default='false');p.add_argument('--notifications',choices=['true','false'],default='false');a=p.parse_args()
 if not a.rds_host.endswith('.rds.amazonaws.com'):p.error('Private RDS hostname required')
 ips=sorted({str(ipaddress.ip_address(x[4][0])) for x in socket.getaddrinfo(a.rds_host,5432,socket.AF_INET)})
 if not ips or not all(ipaddress.ip_address(ip).is_private for ip in ips):p.error('Private RDS addresses required')
 import yaml
 docs=list(yaml.safe_load_all(subprocess.check_output(['kubectl','kustomize',str(a.project/'k8s/overlays/academy')],text=True,encoding='utf-8')))
 for d in docs:
  if d['kind']=='NetworkPolicy':
   for rule in d['spec'].get('egress',[]):
    if any(port.get('port')==5432 for port in rule.get('ports',[])):rule['to']=[{'ipBlock':{'cidr':ip+'/32'}} for ip in ips]
  if d['kind']=='ConfigMap' and d['metadata']['name']=='billing-service-settings':
   d['data']['PAYMENTS_ENABLED']=a.payments;d['data']['NOTIFICATIONS_ENABLED']=a.notifications
  if d['kind']=='Deployment':d['spec']['template']['spec']['containers'][0]['image']=a.image
 Path(a.output).write_text(yaml.safe_dump_all(docs,sort_keys=False),encoding='utf-8')
if __name__=='__main__':main()
