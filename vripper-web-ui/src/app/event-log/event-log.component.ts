import {ChangeDetectionStrategy, Component, NgZone, OnDestroy, ViewChild} from '@angular/core';
import {GridOptions} from 'ag-grid-community';
import {WsConnectionService} from '../services/ws-connection.service';
import {EventLogDatasource} from './event-log.datasource';
import {StatusRendererNative} from '../grid-custom-cells/status-renderer.native';
import {EventLogService} from '../services/event-log.service';
import {EventMessageRendererNative} from '../grid-custom-cells/event-message-renderer.native';
import {AgGridAngular} from "ag-grid-angular";

@Component({
  selector: 'app-event-log',
  templateUrl: './event-log.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class EventLogComponent implements OnDestroy {
  gridOptions: GridOptions;
  @ViewChild('agGrid') agGrid!: AgGridAngular;
  dataSource!: EventLogDatasource;

  constructor(
    private wsConnection: WsConnectionService,
    private zone: NgZone,
    private eventLogService: EventLogService
  ) {
    this.gridOptions = <GridOptions>{
      columnDefs: [
        {
          headerName: 'Time',
          field: 'time',
          sort: 'desc',
          width: 250,
          maxWidth: 250
        }, {
          headerName: 'Type',
          field: 'type',
          valueGetter: (params => {
            switch (params.data.type) {
              case 'POST':
                return '🖼️ New gallery';
              case 'THANKS':
                return '👍 Sending a like ';
              case 'SCAN':
                return '🔍 Links scan';
              case 'METADATA':
              case 'METADATA_CACHE_MISS':
                return '🗄️ Loading post metadata';
              case 'QUEUED':
              case 'QUEUED_CACHE_MISS':
                return '📋 Loading multi-post link';
              case 'DOWNLOAD':
                return '📥 Download';
              default:
                return params.data.type;
            }
          }),
          width: 250,
          maxWidth: 250
        }, {
          headerName: 'Status',
          field: 'status',
          cellRenderer: 'nativeStatusCellRenderer',
          width: 150,
          maxWidth: 150
        }, {
          headerName: 'Message',
          field: 'message',
          flex: 1,
          cellRenderer: 'messageCellRenderer',
          cellRendererParams: {
            eventLogService: this.eventLogService
          },
        }
      ],
      defaultColDef: {
        sortable: true,
        resizable: true
      },
      rowHeight: 26,
      headerHeight: 35,
      animateRows: true,
      rowSelection: 'single',
      suppressRowDeselection: false,
      rowData: [],
      components: {
        nativeStatusCellRenderer: StatusRendererNative,
        messageCellRenderer: EventMessageRendererNative,
      },
      overlayLoadingTemplate: '<span></span>',
      overlayNoRowsTemplate: '<span></span>',
      getRowId: row => row.data['id'],
      onGridReady: () => {
        this.eventLogService.setGridApi(this.agGrid.api);
        this.dataSource = new EventLogDatasource(this.wsConnection, this.agGrid.api, this.zone);
        this.dataSource.connect();
      },
      onRowDataUpdated: () => this.eventLogService.setCount(this.agGrid.api.getDisplayedRowCount()),
    };
  }

  ngOnDestroy(): void {
    if (this.dataSource) {
      this.dataSource.disconnect();
    }
  }
}
